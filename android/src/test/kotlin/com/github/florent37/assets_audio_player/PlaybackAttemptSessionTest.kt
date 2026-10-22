package com.github.florent37.assets_audio_player

import android.content.Context
import android.support.v4.media.session.PlaybackStateCompat
import com.github.florent37.assets_audio_player.notification.NotificationSettings
import com.github.florent37.assets_audio_player.notification.fetchNotificationSettings
import com.github.florent37.assets_audio_player.playerimplem.DurationMS
import com.github.florent37.assets_audio_player.playerimplem.PlayerImplem
import com.github.florent37.assets_audio_player.stopwhencall.StopWhenCall
import io.flutter.embedding.engine.plugins.FlutterPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one reply of an open; a second answer fails the test, as Flutter's "Reply already submitted". */
private class RecordingReply : PlaybackAttemptSession.OpenReply {
    var successCount = 0
    val errors = mutableListOf<String>()

    private fun guard() {
        check(successCount == 0 && errors.isEmpty()) { "Reply already submitted" }
    }

    override fun success() {
        guard()
        successCount += 1
    }

    override fun error(code: String, message: String?) {
        guard()
        errors.add(code)
    }
}

/** Fake player that fails on every transport operation after release. */
private class RecordingPlayerImplem : PlayerImplem(onFinished = {}, onBuffering = {}, onError = {}) {
    var stopCount = 0
    var releaseCount = 0
    private var released = false

    override var loopSingleAudio: Boolean = false
    override val isPlaying: Boolean
        get() {
            requireActive()
            return false
        }
    override val currentPositionMs: Long
        get() {
            requireActive()
            return 0
        }

    private fun requireActive() {
        check(!released) { "transport used after release" }
    }

    override fun stop() {
        requireActive()
        stopCount += 1
    }

    override fun play() = requireActive()

    override fun pause() = requireActive()

    override fun getSessionId(listener: (Int) -> Unit) = requireActive()

    override suspend fun open(
        context: Context,
        flutterAssets: FlutterPlugin.FlutterAssets,
        assetAudioPath: String?,
        audioType: String,
        networkHeaders: Map<*, *>?,
        assetAudioPackage: String?,
        drmConfiguration: Map<*, *>?
    ): DurationMS {
        requireActive()
        return 0
    }

    override fun release() {
        requireActive()
        releaseCount += 1
        released = true
    }

    override fun seekTo(to: Long) = requireActive()

    override fun setVolume(volume: Float) = requireActive()

    override fun setPlaySpeed(playSpeed: Float) = requireActive()

    override fun setPitch(pitch: Float) = requireActive()
}

/** One scheduled action of the manual scheduler; tests deliver it even after cancel to simulate a race. */
private class ManualAction(val delayMs: Long, val action: () -> Unit) : PlaybackAttemptSession.Cancellable {
    var cancelled = false

    override fun cancel() {
        cancelled = true
    }

    fun deliver() {
        action()
    }
}

/** The Pomodoro's real prepared settings: every transport control off, play and pause on. */
private val POMODORO_PREPARED_SETTINGS = NotificationSettings(
        nextEnabled = false,
        playPauseEnabled = true,
        prevEnabled = false,
        seekBarEnabled = false,
        stopEnabled = false,
        previousIcon = null,
        stopIcon = null,
        playIcon = null,
        nextIcon = null,
        pauseIcon = null
)

/** Readable names for the production prepared action bitset. */
private fun actionNames(actions: Long): Set<String> {
    val names = mutableSetOf<String>()
    if (actions and PlaybackStateCompat.ACTION_PLAY != 0L) names.add("play")
    if (actions and PlaybackStateCompat.ACTION_PAUSE != 0L) names.add("pause")
    if (actions and PlaybackStateCompat.ACTION_PLAY_PAUSE != 0L) names.add("play-pause")
    if (actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS != 0L) names.add("previous")
    if (actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT != 0L) names.add("next")
    if (actions and PlaybackStateCompat.ACTION_SEEK_TO != 0L) names.add("seek")
    return names
}

/** Simulated card world: the visible card, its owner player, the manual scheduler and the log lines. */
private class SimulatedCardPort(private val shownBy: String) : PlaybackAttemptSession.Port {
    var cardVisible = false
    var cardPlaying = false
    var cardOwnerPlayer: String? = null
    var showCount = 0
    var hideCount = 0
    var currentTransport: PlayerImplem? = null
    val playbackStatePublications = mutableListOf<Boolean>()
    var expiredEventCount = 0
    val logs = mutableListOf<String>()
    val actions = mutableListOf<ManualAction>()

    override fun showPreparedCard(playing: Boolean) {
        showCount += 1
        cardVisible = true
        cardPlaying = playing
        cardOwnerPlayer = shownBy
    }

    override fun publishPreparedPlaybackState(playing: Boolean) {
        playbackStatePublications.add(playing)
    }

    override fun notifyTransportExpired() {
        expiredEventCount += 1
    }

    override fun cardOwnerPlayerId(): String? = cardOwnerPlayer

    override fun hideCard() {
        hideCount += 1
        cardVisible = false
        cardPlaying = false
        cardOwnerPlayer = null
    }

    override fun publishTransport(player: PlayerImplem) {
        currentTransport = player
    }

    override fun releaseTransport(player: PlayerImplem) {
        if (currentTransport === player) {
            currentTransport = null
        }
        player.stop()
        player.release()
    }

    var playCount = 0
    var pauseCount = 0

    override fun playTransport() {
        playCount += 1
    }

    override fun pauseTransport() {
        pauseCount += 1
    }

    override fun acknowledgeForeground() = Unit

    override fun schedule(delayMs: Long, action: () -> Unit): PlaybackAttemptSession.Cancellable {
        val scheduled = ManualAction(delayMs, action)
        actions.add(scheduled)
        return scheduled
    }

    override fun log(line: String) {
        logs.add(line)
    }
}

/** OP-1 to OP-8: the session's owner guards and single reply, read from simulated card and player state. */
class PlaybackAttemptSessionTest {

    private fun newSession(port: SimulatedCardPort, bound: PreparationBound): PlaybackAttemptSession {
        return PlaybackAttemptSession(playerId = "pomodoro", bound = bound, port = port, boundMs = 10_000)
    }

    @Test
    fun `OP-1 openStarted keeps the bound armed`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val attempt = session.openStarted(RecordingReply())
        val player = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(attempt, player))
        assertEquals(1, port.logs.count { it.startsWith("open answered gen=") && it.contains("result=success") })
        assertEquals(0, player.stopCount)
        assertEquals(0, player.releaseCount)
        assertTrue(port.cardVisible)
        assertTrue(port.cardPlaying)
    }

    @Test
    fun `OP-2 an open stalled past the bound answers OPEN_TIMEOUT once and hides the owned card`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        port.actions.last().deliver()
        assertEquals(listOf("OPEN_TIMEOUT"), reply.errors)
        assertEquals(1, port.hideCount)
        assertFalse(port.cardVisible)
        val player = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(attempt, player))
        assertEquals(1, player.stopCount)
        assertEquals(1, player.releaseCount)
    }

    @Test
    fun `OP-3 an open that fails answers once with its error and hides the owner card`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        session.openFailed(attempt, IllegalStateException("boom"))
        assertEquals(listOf("OPEN_ERROR"), reply.errors)
        assertEquals(1, port.hideCount)
        assertFalse(port.cardVisible)
    }

    @Test
    fun `OP-4 a late success after expiry or a terminal stop releases the late player and answers nothing`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val afterExpiry = RecordingReply()
        val expiredAttempt = session.openStarted(afterExpiry)
        port.actions.last().deliver()
        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(expiredAttempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
        assertEquals(1, afterExpiry.errors.size)
        assertEquals(0, afterExpiry.successCount)

        session.prepare()
        val afterStop = RecordingReply()
        val stoppedAttempt = session.openStarted(afterStop)
        session.terminalStop(cause = "user-stop")
        val otherLatePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(stoppedAttempt, otherLatePlayer))
        assertEquals(1, otherLatePlayer.stopCount)
        assertEquals(1, otherLatePlayer.releaseCount)
        assertEquals(1, afterStop.errors.size)
        assertEquals(0, afterStop.successCount)
    }

    @Test
    fun `OP-5 an old generation expiry after Breath took the card leaves the Breath card visible`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val reply = RecordingReply()
        session.openStarted(reply)
        port.cardOwnerPlayer = "breath"
        port.actions.last().deliver()
        assertEquals(listOf("OPEN_TIMEOUT"), reply.errors)
        assertEquals(0, port.hideCount)
        assertTrue(port.cardVisible)
        assertEquals("breath", port.cardOwnerPlayer)
    }

    @Test
    fun `OP-6 internal replacement keeps the bound and a terminal stop answers the pending open once`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val first = RecordingReply()
        val firstAttempt = session.openStarted(first)
        val firstPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(firstAttempt, firstPlayer))

        val second = RecordingReply()
        val secondAttempt = session.openStarted(second)
        session.replacement()
        assertEquals(1, firstPlayer.stopCount)
        assertEquals(1, firstPlayer.releaseCount)
        val secondPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(secondAttempt, secondPlayer))
        assertEquals(1, second.successCount)

        session.prepare()
        val pending = RecordingReply()
        val pendingAttempt = session.openStarted(pending)
        session.terminalStop(cause = "user-stop")
        assertEquals(listOf("OPEN_CANCELLED"), pending.errors)
        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(pendingAttempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
        port.actions.last().deliver()
        assertEquals(1, pending.errors.size)
    }

    @Test
    fun `OP-7 a prepare after a terminal stop is a fresh generation the old expiry and late open cannot touch`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val firstReply = RecordingReply()
        val firstAttempt = session.openStarted(firstReply)
        session.terminalStop(cause = "user-stop")

        val staleExpiry = port.actions.first()
        session.prepare()
        val secondReply = RecordingReply()
        val secondAttempt = session.openStarted(secondReply)

        staleExpiry.deliver()
        assertEquals(0, secondReply.errors.size)
        assertEquals(0, secondReply.successCount)
        assertTrue(port.cardVisible)
        assertTrue(port.cardPlaying)

        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(firstAttempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
        assertEquals(0, secondReply.errors.size)

        val currentPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(secondAttempt, currentPlayer))
        assertEquals(1, secondReply.successCount)
        assertEquals(0, currentPlayer.stopCount)
        assertEquals(0, currentPlayer.releaseCount)
    }

    @Test
    fun `OP-8 the first playback start disarms the bound and every path answers exactly once`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val started = RecordingReply()
        val startedAttempt = session.openStarted(started)
        val playing = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(startedAttempt, playing))
        session.playbackStarted()
        port.actions.last().deliver()
        assertEquals(1, started.successCount)
        assertEquals(0, started.errors.size)
        assertEquals(0, playing.stopCount)
        assertTrue(port.cardVisible)
        session.terminalStop(cause = "dispose")
        assertEquals(1, started.successCount)
        assertEquals(0, started.errors.size)

        session.prepare()
        val timedOut = RecordingReply()
        val timedOutAttempt = session.openStarted(timedOut)
        port.actions.last().deliver()
        assertEquals(listOf("OPEN_TIMEOUT"), timedOut.errors)
        session.terminalStop(cause = "user-stop")
        assertEquals(1, timedOut.errors.size)
        assertEquals(0, timedOut.successCount)
        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(timedOutAttempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
    }

    @Test
    fun `OP-9 an ordinary open without a preparation succeeds, answers once and is never released`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        val player = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(attempt, player))
        assertEquals(1, reply.successCount)
        assertEquals(0, reply.errors.size)
        assertEquals(0, player.stopCount)
        assertEquals(0, player.releaseCount)
        assertEquals(0, port.showCount) // an ordinary open posts no prepared card
        assertEquals(player, port.currentTransport)
    }

    @Test
    fun `OP-10 a replacement open after playback started succeeds and releases the replaced player once`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val first = RecordingReply()
        val firstAttempt = session.openStarted(first)
        val firstPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(firstAttempt, firstPlayer))
        session.playbackStarted() // the first playback start disarms the expiry, not the attempt

        val second = RecordingReply()
        val secondAttempt = session.openStarted(second)
        session.replacement() // Player.open's internal stop: releases the replaced player, keeps the bound
        assertEquals(1, firstPlayer.stopCount)
        assertEquals(1, firstPlayer.releaseCount)
        val secondPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(secondAttempt, secondPlayer))
        assertEquals(1, second.successCount)
        assertEquals(0, second.errors.size)
        assertEquals(0, secondPlayer.stopCount)
        assertEquals(0, secondPlayer.releaseCount)
        assertEquals(secondPlayer, port.currentTransport)
    }

    @Test
    fun `OP-11 a cancelled ordinary open's late completion answers nothing more and releases the late player`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        session.terminalStop(cause = "user-stop")
        assertEquals(listOf("OPEN_CANCELLED"), reply.errors)
        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(attempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
        assertEquals(1, reply.errors.size)
        assertEquals(0, reply.successCount)
    }

    @Test
    fun `OP-12 a cancelled attempt completing after a newer accepted open cannot overwrite the current player`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val cancelled = RecordingReply()
        val cancelledAttempt = session.openStarted(cancelled)
        session.terminalStop(cause = "user-stop")
        assertEquals(listOf("OPEN_CANCELLED"), cancelled.errors)

        val current = RecordingReply()
        val currentAttempt = session.openStarted(current)
        val currentPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(currentAttempt, currentPlayer))
        session.playbackStarted() // the current player starts while the cancelled open is still in flight

        assertFalse(session.preparedPlayPause(playing = false)) // a Pause dispatches to the current player
        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(cancelledAttempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
        assertEquals(currentPlayer, port.currentTransport)
        assertEquals(1, current.successCount)
        assertEquals(0, current.errors.size)
        assertEquals(listOf("OPEN_CANCELLED"), cancelled.errors)
    }

    @Test
    fun `OP-13 an accepted player released by its preparation expiry clears the wrapper transport`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        val player = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(attempt, player))
        assertEquals(player, port.currentTransport)

        port.actions.last().deliver() // the prepared bound expires before the first playback start
        assertEquals(1, player.stopCount)
        assertEquals(1, player.releaseCount)
        assertNull(port.currentTransport) // no stale wrapper reference after the timeout
        assertEquals(1, reply.successCount)
        assertEquals(0, reply.errors.size)
    }

    @Test
    fun `OP-14 a terminal stop after acceptance clears the wrapper transport`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        val player = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(attempt, player))
        assertEquals(player, port.currentTransport)

        session.terminalStop(cause = "dispose")
        assertEquals(1, player.stopCount)
        assertEquals(1, player.releaseCount)
        assertNull(port.currentTransport)
        assertEquals(1, reply.successCount)
        assertEquals(0, reply.errors.size)
    }

    @Test
    fun `OP-15 rejecting an invalid completion leaves its unanswered reply for terminal cancellation`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val bound = PreparationBound()
        val session = newSession(port, bound)
        session.prepare()
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        bound.terminal(1) // Simulates validity changing before the completion reaches the session.

        val rejectedPlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(attempt, rejectedPlayer))
        assertEquals(0, reply.successCount)
        assertEquals(0, reply.errors.size)
        session.terminalStop(cause = "user-stop")
        assertEquals(listOf("OPEN_CANCELLED"), reply.errors)
    }

    @Test
    fun `OP-16 a replacement open settles the superseded open once and a late success cannot touch it`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val superseded = RecordingReply()
        val supersededAttempt = session.openStarted(superseded)

        val replacement = RecordingReply()
        val replacementAttempt = session.openStarted(replacement) // starts before the first open settles
        assertEquals(listOf("OPEN_SUPERSEDED"), superseded.errors) // answered exactly once, at admission
        assertEquals(0, port.hideCount) // the newer attempt owns the card flow

        val replacementPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(replacementAttempt, replacementPlayer))
        assertEquals(1, replacement.successCount)

        val latePlayer = RecordingPlayerImplem()
        assertFalse(session.openSucceeded(supersededAttempt, latePlayer))
        assertEquals(1, latePlayer.stopCount)
        assertEquals(1, latePlayer.releaseCount)
        assertEquals(0, superseded.successCount)
        assertEquals(1, superseded.errors.size) // no second terminal reply

        assertEquals(replacementPlayer, port.currentTransport)
        session.play()
        assertEquals(1, port.playCount)

        val later = RecordingReply()
        val laterAttempt = session.openStarted(later)
        session.replacement() // Player.open's internal stop before the next open
        val laterPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(laterAttempt, laterPlayer))
        assertEquals(1, later.successCount)
    }

    @Test
    fun `OP-17 a superseded open failing later answers nothing more and never reports the newer attempt`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val superseded = RecordingReply()
        val supersededAttempt = session.openStarted(superseded)

        val replacement = RecordingReply()
        val replacementAttempt = session.openStarted(replacement)
        assertEquals(listOf("OPEN_SUPERSEDED"), superseded.errors)

        val replacementPlayer = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(replacementAttempt, replacementPlayer))
        session.playbackStarted() // the newer attempt started playing; the bound is disarmed

        session.openFailed(supersededAttempt, IllegalStateException("boom"))
        assertEquals(1, superseded.errors.size) // the late failure answers nothing more
        assertEquals(0, replacement.errors.size) // never reported as the newer attempt's playback failure
        assertEquals(replacementPlayer, port.currentTransport)
        session.pause()
        assertEquals(1, port.pauseCount) // the newer attempt remains controlled
    }

    @Test
    fun `OP-18 an accepted transport expiry notifies Dart once and other expiries do not`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        val reply = RecordingReply()
        val attempt = session.openStarted(reply)
        val player = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(attempt, player))

        port.actions.last().deliver() // the prepared bound expires over the accepted, paused transport
        assertEquals(1, player.stopCount)
        assertEquals(1, player.releaseCount)
        assertEquals(1, port.expiredEventCount) // Dart learns the playlist is no longer playable

        // A pending-open expiry answers OPEN_TIMEOUT and reports no expired transport.
        val pendingPort = SimulatedCardPort(shownBy = "pomodoro")
        val pendingSession = newSession(pendingPort, PreparationBound())
        pendingSession.prepare()
        val pendingReply = RecordingReply()
        pendingSession.openStarted(pendingReply)
        pendingPort.actions.last().deliver()
        assertEquals(listOf("OPEN_TIMEOUT"), pendingReply.errors)
        assertEquals(0, pendingPort.expiredEventCount)

        // A prepared-only expiry never had a transport, so nothing expired for Dart.
        val preparedPort = SimulatedCardPort(shownBy = "pomodoro")
        val preparedSession = newSession(preparedPort, PreparationBound())
        preparedSession.prepare()
        preparedPort.actions.last().deliver()
        assertEquals(0, preparedPort.expiredEventCount)
    }

    @Test
    fun `OP-19 owner-guarded terminal removal clears the prepared system playback state`() {
        val stopPort = SimulatedCardPort(shownBy = "pomodoro")
        val stopSession = newSession(stopPort, PreparationBound())
        stopSession.prepare()
        assertEquals(listOf(true), stopPort.playbackStatePublications)
        stopSession.terminalStop(cause = "user-stop")
        assertEquals(1, stopPort.hideCount)
        assertEquals(listOf(true, false), stopPort.playbackStatePublications)

        val expiryPort = SimulatedCardPort(shownBy = "pomodoro")
        val expirySession = newSession(expiryPort, PreparationBound())
        expirySession.prepare()
        expiryPort.actions.last().deliver()
        assertEquals(1, expiryPort.hideCount)
        assertEquals(listOf(true, false), expiryPort.playbackStatePublications)

        val failPort = SimulatedCardPort(shownBy = "pomodoro")
        val failSession = newSession(failPort, PreparationBound())
        failSession.prepare()
        val reply = RecordingReply()
        val failAttempt = failSession.openStarted(reply)
        failSession.openFailed(failAttempt, IllegalStateException("boom"))
        assertEquals(listOf("OPEN_ERROR"), reply.errors)
        assertEquals(1, failPort.hideCount)
        assertEquals(listOf(true, false), failPort.playbackStatePublications)
    }

    @Test
    fun `OP-20 terminal removal never clears another owner's card or playback state`() {
        val port = SimulatedCardPort(shownBy = "pomodoro")
        val session = newSession(port, PreparationBound())
        session.prepare()
        assertEquals(listOf(true), port.playbackStatePublications)

        port.cardOwnerPlayer = "newer" // another player took the card before this one ended
        session.terminalStop(cause = "user-stop")
        assertEquals(0, port.hideCount) // the newer owner's card is untouched
        assertEquals(listOf(true), port.playbackStatePublications) // and so is its published state
    }

    @Test
    fun `B3 replacement terminal expiry and dispose each release their transport exactly once`() {
        val replacementPort = SimulatedCardPort(shownBy = "pomodoro")
        val replacementSession = newSession(replacementPort, PreparationBound())
        val replacementPlayer = acceptPreparedPlayer(replacementSession)
        replacementSession.replacement()
        assertReleasedOnce(replacementPlayer, replacementPort)
        replacementSession.terminalStop(cause = "user-stop")
        assertReleasedOnce(replacementPlayer, replacementPort)

        val terminalPort = SimulatedCardPort(shownBy = "pomodoro")
        val terminalSession = newSession(terminalPort, PreparationBound())
        val terminalPlayer = acceptPreparedPlayer(terminalSession)
        terminalSession.terminalStop(cause = "user-stop")
        assertReleasedOnce(terminalPlayer, terminalPort)

        val expiryPort = SimulatedCardPort(shownBy = "pomodoro")
        val expirySession = newSession(expiryPort, PreparationBound())
        val expiryPlayer = acceptPreparedPlayer(expirySession)
        expiryPort.actions.last().deliver()
        assertReleasedOnce(expiryPlayer, expiryPort)

        val disposePort = SimulatedCardPort(shownBy = "pomodoro")
        val disposeSession = newSession(disposePort, PreparationBound())
        val disposePlayer = acceptPreparedPlayer(disposeSession)
        disposeSession.terminalStop(cause = "dispose")
        assertReleasedOnce(disposePlayer, disposePort)
    }

    private fun acceptPreparedPlayer(session: PlaybackAttemptSession): RecordingPlayerImplem {
        session.prepare()
        val attempt = session.openStarted(RecordingReply())
        val player = RecordingPlayerImplem()
        assertTrue(session.openSucceeded(attempt, player))
        return player
    }

    private fun assertReleasedOnce(player: RecordingPlayerImplem, port: SimulatedCardPort) {
        assertEquals(1, player.stopCount)
        assertEquals(1, player.releaseCount)
        assertNull(port.currentTransport)
    }
}

/** One recording port carrying both the card and the focus effects, as the production Player wires both. */
private class PlayerEffectsPort : PlaybackAttemptSession.Port, FocusCoordinator.Port {
    var showCount = 0
    var hideCount = 0
    var requestFocusCount = 0
    var startTransportCount = 0
    var pauseTransportCount = 0
    var promoteCount = 0
    var foregroundAckCount = 0
    val advertisedPlayingStates = mutableListOf<Boolean>()
    val publishedPlaybackStates = mutableListOf<Boolean>()
    val publishedPlaybackActions = mutableListOf<Set<String>>()
    val logs = mutableListOf<String>()
    override fun showPreparedCard(playing: Boolean) {
        showCount += 1
        advertisedPlayingStates.add(playing)
    }

    override fun publishPreparedPlaybackState(playing: Boolean) {
        publishedPlaybackStates.add(playing)
        publishedPlaybackActions.add(actionNames(preparedPlaybackActions(POMODORO_PREPARED_SETTINGS)))
    }

    override fun cardOwnerPlayerId(): String? = "pomodoro"

    override fun hideCard() {
        hideCount += 1
    }

    override fun publishTransport(player: PlayerImplem) = Unit

    override fun notifyTransportExpired() = Unit

    override fun releaseTransport(player: PlayerImplem) {
        player.stop()
        player.release()
    }

    override fun playTransport() {
        requestFocusCount += 1
    }

    override fun pauseTransport() {
        pauseTransportCount += 1
    }

    override fun acknowledgeForeground() {
        foregroundAckCount += 1
    }

    override fun schedule(delayMs: Long, action: () -> Unit): PlaybackAttemptSession.Cancellable {
        return PlaybackAttemptSession.Cancellable { }
    }

    override fun log(line: String) {
        logs.add(line)
    }

    override fun requestFocus(): StopWhenCall.AudioState {
        requestFocusCount += 1
        return StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
    }

    override fun startTransport() {
        startTransportCount += 1
    }

    override fun isTransportPlaying(): Boolean = false

    override fun promoteCard() {
        promoteCount += 1
    }
}

/** Exercises the prepared player's play and pause dispatch before the transport exists. */
class PreparedPlayPauseTest {

    @Test
    fun `SP-1 a prepared player's play and pause repost the card and never touch transport or focus`() {
        val port = PlayerEffectsPort()
        port.publishedPlaybackStates.add(false) // Shared MediaSession state retained from the previous player.
        port.publishedPlaybackActions.add(setOf("play"))
        val session = PlaybackAttemptSession(
                playerId = "pomodoro",
                bound = PreparationBound(),
                port = port,
                boundMs = 10_000
        )
        session.prepare()

        session.play()
        session.pause()
        session.onServiceForeground()

        assertEquals(3, port.showCount) // prepare, then the play and the pause reposts
        assertEquals(listOf(true, true, false), port.advertisedPlayingStates)
        assertEquals(listOf(false, true, true, false), port.publishedPlaybackStates)
        assertEquals(
            listOf(
                setOf("play"),
                setOf("play", "pause", "play-pause"),
                setOf("play", "pause", "play-pause"),
                setOf("play", "pause", "play-pause")
            ),
            port.publishedPlaybackActions
        )
        assertEquals(0, port.hideCount)
        assertEquals(0, port.requestFocusCount)
        assertEquals(0, port.startTransportCount)
        assertEquals(0, port.pauseTransportCount)
        assertEquals(0, port.promoteCount)
        assertEquals(1, port.foregroundAckCount)
        assertTrue(port.logs.contains("foreground ack gen=1"))
        assertFalse(port.logs.any { it.startsWith("focus request") })
    }

    @Test
    fun `SP-2 the prepared card advertises exactly the production mapping of its prepared settings`() {
        // The Pomodoro's prepared settings allow only play and pause; the mapping must not invent controls.
        val pomodoroBase = PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE
        assertEquals(pomodoroBase, preparedPlaybackActions(POMODORO_PREPARED_SETTINGS))

        // The same settings arriving the way Dart sends them map identically.
        val fetched = fetchNotificationSettings(mapOf(
                "notif.settings.nextEnabled" to false,
                "notif.settings.playPauseEnabled" to true,
                "notif.settings.prevEnabled" to false,
                "notif.settings.seekBarEnabled" to false,
                "notif.settings.stopEnabled" to false))
        assertEquals(pomodoroBase, preparedPlaybackActions(fetched))

        // Each enabled control adds exactly its own action to the base; nothing else appears.
        fun settingsWith(playPause: Boolean = true, prev: Boolean = false, next: Boolean = false,
                         seek: Boolean = false) = NotificationSettings(
                nextEnabled = next, playPauseEnabled = playPause, prevEnabled = prev, seekBarEnabled = seek,
                stopEnabled = false, previousIcon = null, stopIcon = null, playIcon = null,
                nextIcon = null, pauseIcon = null)
        assertEquals(pomodoroBase or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS,
                preparedPlaybackActions(settingsWith(prev = true)))
        assertEquals(pomodoroBase or PlaybackStateCompat.ACTION_SKIP_TO_NEXT,
                preparedPlaybackActions(settingsWith(next = true)))
        assertEquals(pomodoroBase or PlaybackStateCompat.ACTION_SEEK_TO,
                preparedPlaybackActions(settingsWith(seek = true)))

        // playPauseEnabled only shapes the card layout; the session keeps its play and pause actions.
        assertEquals(pomodoroBase, preparedPlaybackActions(settingsWith(playPause = false)))
    }
}
