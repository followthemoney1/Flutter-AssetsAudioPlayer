package com.github.florent37.assets_audio_player

/** Play intent, interruption state and the one focus retry of a Play attempt. */
class PlaybackFocusIntent {
    enum class Action { NONE, START, PAUSE_TRANSPORT, REQUEST_FOCUS_RETRY }

    private var playRequested = false
    private var awaitingGrant = false
    private var retryArmed = false

    /** intent = play, retry budget = 1. */
    fun requestPlay() {
        playRequested = true
        awaitingGrant = true
        retryArmed = true
    }

    /** intent = paused, retry and awaiting cleared. */
    fun pause() {
        playRequested = false
        awaitingGrant = false
        retryArmed = false
    }

    /** Everything cleared. */
    fun stop() {
        pause()
    }

    /** Granted: START once; denied: arms the retry only if !isRetry. */
    fun onRequestResult(granted: Boolean, isRetry: Boolean): Action {
        if (granted) {
            retryArmed = false
            if (playRequested) {
                awaitingGrant = false
                return Action.START
            }
            return Action.NONE
        }
        if (!isRetry) {
            retryArmed = true
        }
        return Action.NONE
    }

    /** PAUSE_TRANSPORT; awaiting = intent == play. */
    fun onFocusLost(): Action {
        awaitingGrant = playRequested
        return Action.PAUSE_TRANSPORT
    }

    /** START only if awaiting && intent == play && !isPlaying. */
    fun onFocusGranted(isPlaying: Boolean, resumeAfterInterruption: Boolean): Action {
        if (resumeAfterInterruption && awaitingGrant && playRequested && !isPlaying) {
            awaitingGrant = false
            return Action.START
        }
        return Action.NONE
    }

    /** REQUEST_FOCUS_RETRY only if the armed retry is unused. */
    fun onServiceForeground(): Action {
        if (retryArmed) {
            retryArmed = false
            return Action.REQUEST_FOCUS_RETRY
        }
        return Action.NONE
    }
}
