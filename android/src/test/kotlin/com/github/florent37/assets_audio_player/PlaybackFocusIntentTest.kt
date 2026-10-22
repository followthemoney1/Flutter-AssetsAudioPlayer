package com.github.florent37.assets_audio_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Decision table of [PlaybackFocusIntent]: the play intent, the interruption state and the one retry. */
class PlaybackFocusIntentTest {

    @Test
    fun `requestPlay arms a one retry budget and a granted request result starts once`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        // The granted result is the one start owner of an ordinary Play.
        assertEquals(PlaybackFocusIntent.Action.START, intent.onRequestResult(granted = true, isRetry = false))
        // The awaiting state was consumed with the start: an unsolicited grant replay starts nothing.
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
        // The retry budget was consumed with the start.
        assertEquals(PlaybackFocusIntent.Action.NONE, intent.onServiceForeground())
    }

    @Test
    fun `a denied ordinary result arms the retry and a denied retry does not rearm`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        assertEquals(PlaybackFocusIntent.Action.NONE, intent.onRequestResult(granted = false, isRetry = false))
        assertEquals(PlaybackFocusIntent.Action.REQUEST_FOCUS_RETRY, intent.onServiceForeground())
        // The retry was used; a second foreground acknowledgement must not retry again.
        assertEquals(PlaybackFocusIntent.Action.NONE, intent.onServiceForeground())
    }

    @Test
    fun `a denied play and denied retry during an interruption keep the eventual resume`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        assertEquals(PlaybackFocusIntent.Action.START, intent.onRequestResult(granted = true, isRetry = false))
        assertEquals(PlaybackFocusIntent.Action.PAUSE_TRANSPORT, intent.onFocusLost())

        intent.requestPlay()
        assertEquals(PlaybackFocusIntent.Action.NONE, intent.onRequestResult(granted = false, isRetry = false))
        assertEquals(PlaybackFocusIntent.Action.REQUEST_FOCUS_RETRY, intent.onServiceForeground())
        assertEquals(PlaybackFocusIntent.Action.NONE, intent.onRequestResult(granted = false, isRetry = true))

        assertEquals(
            PlaybackFocusIntent.Action.START,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
    }

    @Test
    fun `pause and stop withdraw the retained resume after a denied play`() {
        val paused = PlaybackFocusIntent()
        paused.requestPlay()
        paused.onFocusLost()
        paused.onRequestResult(granted = false, isRetry = false)
        paused.pause()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            paused.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )

        val stopped = PlaybackFocusIntent()
        stopped.requestPlay()
        stopped.onFocusLost()
        stopped.onRequestResult(granted = false, isRetry = false)
        stopped.stop()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            stopped.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
    }

    @Test
    fun `a loss arms the resume for a play intent and the regain resumes once`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        intent.onRequestResult(granted = true, isRetry = false)
        assertEquals(PlaybackFocusIntent.Action.PAUSE_TRANSPORT, intent.onFocusLost())
        assertEquals(
            PlaybackFocusIntent.Action.START,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
        // The resume is consumed; a repeated regain (another player's grant) starts nothing.
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
    }

    @Test
    fun `a loss with a paused or fresh intent never resumes`() {
        val paused = PlaybackFocusIntent()
        paused.requestPlay()
        paused.onRequestResult(granted = true, isRetry = false)
        paused.pause()
        paused.onFocusLost()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            paused.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )

        val fresh = PlaybackFocusIntent()
        assertEquals(PlaybackFocusIntent.Action.PAUSE_TRANSPORT, fresh.onFocusLost())
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            fresh.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
    }

    @Test
    fun `a grant without resumeAfterInterruption never starts`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        intent.onFocusLost()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = false)
        )
    }

    @Test
    fun `a grant while the transport already plays starts nothing`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        intent.onFocusLost()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            intent.onFocusGranted(isPlaying = true, resumeAfterInterruption = true)
        )
    }

    @Test
    fun `pause and stop clear the intent awaiting state and retry`() {
        val paused = PlaybackFocusIntent()
        paused.requestPlay()
        paused.pause()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            paused.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
        assertEquals(PlaybackFocusIntent.Action.NONE, paused.onServiceForeground())
        assertEquals(PlaybackFocusIntent.Action.NONE, paused.onRequestResult(granted = true, isRetry = false))

        val stopped = PlaybackFocusIntent()
        stopped.requestPlay()
        stopped.stop()
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            stopped.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
        assertEquals(PlaybackFocusIntent.Action.NONE, stopped.onServiceForeground())
        assertEquals(PlaybackFocusIntent.Action.NONE, stopped.onRequestResult(granted = true, isRetry = false))
    }

    @Test
    fun `a granted result after stop starts nothing`() {
        val intent = PlaybackFocusIntent()
        intent.requestPlay()
        intent.stop()
        assertFalse(intent.onRequestResult(granted = true, isRetry = false) == PlaybackFocusIntent.Action.START)
        assertTrue(intent.onFocusLost() == PlaybackFocusIntent.Action.PAUSE_TRANSPORT)
        assertEquals(
            PlaybackFocusIntent.Action.NONE,
            intent.onFocusGranted(isPlaying = false, resumeAfterInterruption = true)
        )
    }
}
