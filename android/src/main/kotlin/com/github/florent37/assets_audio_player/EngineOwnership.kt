package com.github.florent37.assets_audio_player

/** Which Flutter engine owns the players; the engine that shows the app wins. */
class EngineOwnership {
    enum class Decision { TAKE, WAIT, KEEP, TRANSFER, RELEASE, IGNORE }

    var owner: Int? = null
        private set

    /** No owner: TAKE (owner = engineId); otherwise WAIT. */
    fun attached(engineId: Int): Decision {
        if (owner == null) {
            owner = engineId
            return Decision.TAKE
        }
        return Decision.WAIT
    }

    /** Owner: KEEP; another engine: TRANSFER (owner = engineId). */
    fun activityAttached(engineId: Int): Decision {
        if (owner == engineId) {
            return Decision.KEEP
        }
        owner = engineId
        return Decision.TRANSFER
    }

    /** Owner: RELEASE (owner = null); others: IGNORE. */
    fun detached(engineId: Int): Decision {
        if (owner == engineId) {
            owner = null
            return Decision.RELEASE
        }
        return Decision.IGNORE
    }
}
