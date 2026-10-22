package com.github.florent37.assets_audio_player

/** The bound on a prepared media card: one generation per prepare, released only by its owner. */
class PreparationBound {
    private var counter: Int = 0
    private var current: Int? = null
    private var armed: Boolean = false

    /** New generation, armed. */
    fun prepare(): Int {
        counter += 1
        current = counter
        armed = true
        return counter
    }

    /** Disarmed. */
    fun playbackStarted(generation: Int) {
        if (generation == current) {
            armed = false
        }
    }

    /** User stop or dispose: disarmed, generation dead. */
    fun terminal(generation: Int) {
        if (generation == current) {
            armed = false
            current = null
        }
    }

    /** True when still armed and current; the expiry consumes the bound. */
    fun expire(generation: Int): Boolean {
        if (generation == current && armed) {
            armed = false
            current = null
            return true
        }
        return false
    }

    /** False after expiry or terminal; playback only disarms the timeout. */
    fun mayStart(generation: Int): Boolean {
        return generation == current
    }
}
