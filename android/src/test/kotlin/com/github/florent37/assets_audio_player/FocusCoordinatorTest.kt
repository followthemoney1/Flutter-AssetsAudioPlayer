package com.github.florent37.assets_audio_player

import com.github.florent37.assets_audio_player.stopwhencall.AudioFocusStrategy
import com.github.florent37.assets_audio_player.stopwhencall.StopWhenCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Focus harness: a JVM subclass of [StopWhenCall] whose requestAudioFocus pings every registered listener
 * synchronously before returning, as StopWhenCallAudioFocus.kt:59-61 does.
 */
private class SynchronousFocusHarness : StopWhenCall() {
    var nextResult: StopWhenCall.AudioState = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
    var requestCount = 0

    override fun requestAudioFocus(audioFocusStrategy: AudioFocusStrategy): StopWhenCall.AudioState {
        requestCount += 1
        val result = nextResult
        pingListeners(result)
        return result
    }

    /** An unsolicited focus change, as the system delivers at an interruption end. */
    fun broadcast(state: StopWhenCall.AudioState) {
        pingListeners(state)
    }

    override fun stop() {
        // The tests never abandon the shared request.
    }
}

/** Simulated transport and card of one player; the assertions read this state, never call mocks. */
private class FakeCoordinatorPort(private val harness: SynchronousFocusHarness) : FocusCoordinator.Port {
    var startCount = 0
    var pauseCount = 0
    var promoteCount = 0
    var playing = false
    val logs = mutableListOf<String>()

    override fun requestFocus(): StopWhenCall.AudioState {
        return harness.requestAudioFocus(
            AudioFocusStrategy.Request(resumeAfterInterruption = true, resumeOthersPlayersAfterDone = true)
        )
    }

    override fun startTransport() {
        startCount += 1
        playing = true
    }

    override fun pauseTransport() {
        pauseCount += 1
        playing = false
    }

    override fun isTransportPlaying(): Boolean = playing

    override fun promoteCard() {
        promoteCount += 1
    }

    override fun log(line: String) {
        logs.add(line)
    }
}

/** Two players' coordinators registered on one harness, as the plugin forwards every change to every player. */
private class TwoPlayers {
    val harness = SynchronousFocusHarness()
    val portA = FakeCoordinatorPort(harness)
    val portB = FakeCoordinatorPort(harness)
    val playerA = FocusCoordinator(PlaybackFocusIntent(), portA)
    val playerB = FocusCoordinator(PlaybackFocusIntent(), portB)

    init {
        // AssetsAudioPlayerPlugin.kt:125-130 forwards every focus change to every player.
        harness.register(object : StopWhenCall.Listener {
            override fun onPhoneStateChanged(audioState: StopWhenCall.AudioState) {
                playerA.onFocusChange(audioState, resumeAfterInterruption = true)
            }
        })
        harness.register(object : StopWhenCall.Listener {
            override fun onPhoneStateChanged(audioState: StopWhenCall.AudioState) {
                playerB.onFocusChange(audioState, resumeAfterInterruption = true)
            }
        })
    }
}

/** FI-1 to FI-7: the focus coordinator wiring on two players over the synchronous focus harness. */
class FocusCoordinatorTest {

    @Test
    fun `FI-1 an ordinary play whose grant arrives synchronously inside the request starts the transport once`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
        players.playerA.play()
        assertEquals(1, players.harness.requestCount)
        assertEquals(1, players.portA.startCount)
        assertTrue(players.portA.playing)
        assertEquals(1, players.portA.promoteCount)
        assertTrue(players.portA.logs.contains("focus request granted=true retry=false"))
    }

    @Test
    fun `FI-2 the second player's grant broadcast to both does not restart the first player`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
        players.playerA.play()
        players.playerB.play()
        assertEquals(1, players.portA.startCount)
        assertEquals(1, players.portB.startCount)
        assertTrue(players.portA.playing)
        assertTrue(players.portB.playing)
    }

    @Test
    fun `FI-3 loss then regain resumes a requested player once`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
        players.playerA.play()
        players.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        assertFalse(players.portA.playing)
        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(2, players.portA.startCount)
        assertTrue(players.portA.playing)
    }

    @Test
    fun `FI-4 pause during a loss stays paused after regain`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
        players.playerA.play()
        players.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        players.playerA.pause()
        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(1, players.portA.startCount)
        assertFalse(players.portA.playing)
    }

    @Test
    fun `FI-5 repeated losses keep the resume`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
        players.playerA.play()
        players.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        players.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(2, players.portA.startCount)
        assertTrue(players.portA.playing)
    }

    @Test
    fun `FI-6 deny foreground deny makes exactly one retry`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.FORBIDDEN
        players.playerA.play()
        assertEquals(1, players.harness.requestCount)
        assertEquals(0, players.portA.startCount)
        assertTrue(players.portA.logs.contains("focus request granted=false retry=false"))
        players.playerA.onServiceForeground()
        assertEquals(2, players.harness.requestCount)
        assertEquals(0, players.portA.startCount)
        assertTrue(players.portA.logs.contains("focus retry consumed"))
        assertTrue(players.portA.logs.contains("focus request granted=false retry=true"))
        // The used retry is not rearmed: a second foreground acknowledgement retries nothing.
        players.playerA.onServiceForeground()
        assertEquals(2, players.harness.requestCount)
    }

    @Test
    fun `B4 denied play and denied retry during a loss still resume after the real grant`() {
        val players = TwoPlayers()
        players.playerA.play()
        players.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        assertFalse(players.portA.playing)

        players.harness.nextResult = StopWhenCall.AudioState.FORBIDDEN
        players.playerA.play()
        players.playerA.onServiceForeground()
        assertEquals(3, players.harness.requestCount)
        assertEquals(1, players.portA.startCount)

        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(2, players.portA.startCount)
        assertTrue(players.portA.playing)
    }

    @Test
    fun `B4 pause and stop after a denied play suppress the later grant`() {
        val paused = TwoPlayers()
        paused.playerA.play()
        paused.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        paused.harness.nextResult = StopWhenCall.AudioState.FORBIDDEN
        paused.playerA.play()
        paused.playerA.pause()
        paused.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(1, paused.portA.startCount)
        assertFalse(paused.portA.playing)

        val stopped = TwoPlayers()
        stopped.playerA.play()
        stopped.harness.broadcast(StopWhenCall.AudioState.FORBIDDEN)
        stopped.harness.nextResult = StopWhenCall.AudioState.FORBIDDEN
        stopped.playerA.play()
        stopped.playerA.stop()
        stopped.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(1, stopped.portA.startCount)
        assertFalse(stopped.portA.playing)
    }

    @Test
    fun `FI-7 success pause stop and dispose clear the retry and the awaiting state`() {
        val players = TwoPlayers()
        players.harness.nextResult = StopWhenCall.AudioState.AUTHORIZED_TO_PLAY

        // Success clears the retry: no foreground retry after a granted request.
        players.playerA.play()
        players.playerA.onServiceForeground()
        assertEquals(1, players.harness.requestCount)

        // Pause clears the awaiting state and the retry.
        players.playerA.pause()
        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(1, players.portA.startCount)
        players.playerA.onServiceForeground()
        assertEquals(1, players.harness.requestCount)

        // Stop clears everything; dispose forwards through the same clear.
        players.playerA.play()
        assertEquals(2, players.portA.startCount)
        players.playerA.stop()
        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(2, players.portA.startCount)
        players.playerA.onServiceForeground()
        assertEquals(2, players.harness.requestCount)
        players.playerA.play()
        assertEquals(3, players.portA.startCount)
        players.playerA.stop()
        players.harness.broadcast(StopWhenCall.AudioState.AUTHORIZED_TO_PLAY)
        assertEquals(3, players.portA.startCount)
        players.playerA.onServiceForeground()
        assertEquals(3, players.harness.requestCount)
    }
}
