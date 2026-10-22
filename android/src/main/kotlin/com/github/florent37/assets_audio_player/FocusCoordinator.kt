package com.github.florent37.assets_audio_player

import com.github.florent37.assets_audio_player.stopwhencall.StopWhenCall

/** Applies [PlaybackFocusIntent] for one player; every effect goes through [port]. */
class FocusCoordinator(private val intent: PlaybackFocusIntent, private val port: Port) {
    interface Port {
        /** The answer also reaches onFocusChange, synchronously. */
        fun requestFocus(): StopWhenCall.AudioState

        fun startTransport()
        fun pauseTransport()
        fun isTransportPlaying(): Boolean

        /** Repost the media card first, so the service is foreground. */
        fun promoteCard()

        fun log(line: String)
    }

    private var inRequest = false

    fun play() {
        // Android 15+ grants audio focus only to a visible app or one with a running foreground service.
        port.promoteCard()
        intent.requestPlay()
        requestFocusAndApply(isRetry = false)
    }

    fun pause() {
        intent.pause()
        port.pauseTransport()
    }

    fun stop() {
        intent.stop()
    }

    fun onFocusChange(state: StopWhenCall.AudioState, resumeAfterInterruption: Boolean) {
        when (state) {
            StopWhenCall.AudioState.AUTHORIZED_TO_PLAY -> {
                // A grant also arrives synchronously for our own request; only a loss earlier makes it a resume.
                if (inRequest) {
                    return
                }
                val action = intent.onFocusGranted(port.isTransportPlaying(), resumeAfterInterruption)
                if (action == PlaybackFocusIntent.Action.START) {
                    port.startTransport()
                }
            }
            StopWhenCall.AudioState.FORBIDDEN -> {
                val action = intent.onFocusLost()
                if (action == PlaybackFocusIntent.Action.PAUSE_TRANSPORT) {
                    port.pauseTransport()
                }
            }
            StopWhenCall.AudioState.REDUCE_VOLUME -> {
                // Ducking stays with the player; the play intent has no duck action.
            }
        }
    }

    fun onServiceForeground() {
        if (intent.onServiceForeground() == PlaybackFocusIntent.Action.REQUEST_FOCUS_RETRY) {
            port.log("focus retry consumed")
            requestFocusAndApply(isRetry = true)
        }
    }

    private fun requestFocusAndApply(isRetry: Boolean) {
        inRequest = true
        try {
            val granted = port.requestFocus() == StopWhenCall.AudioState.AUTHORIZED_TO_PLAY
            port.log("focus request granted=$granted retry=$isRetry")
            val action = intent.onRequestResult(granted, isRetry)
            if (action == PlaybackFocusIntent.Action.START) {
                port.startTransport()
            }
        } finally {
            inRequest = false
        }
    }
}
