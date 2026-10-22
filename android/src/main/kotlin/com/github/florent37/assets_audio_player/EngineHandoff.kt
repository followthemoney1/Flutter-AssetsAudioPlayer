package com.github.florent37.assets_audio_player

/**
 * Applies [EngineOwnership] to plugin instances; owner guards and cleanup order live here.
 * Every effect goes through [Port], one platform call or one read per method, so the class runs on the JVM.
 */
class EngineHandoff(
    private val ownership: EngineOwnership,
    private val port: Port,
) {
    interface Port {
        fun publishOwner(engineId: Int?)          // the static plugin instance every notification path reads
        fun setMethodHandler(engineId: Int)
        fun clearMethodHandler(engineId: Int)
        fun startHeadsetManager(engineId: Int)
        fun stopHeadsetManager(engineId: Int)
        fun registerFocusListener(engineId: Int)
        fun unregisterFocusListener(engineId: Int)
        fun registerMediaButtons(engineId: Int)   // takes the shared media session's callback
        fun abandonFocus(engineId: Int)           // releases that engine's native audio focus request
        fun stopPlayers(engineId: Int)
        fun cardOwnerEngine(): Int?               // engine whose player posted the visible media card
        fun closeCardService()                    // stops the shared notification service
        fun log(line: String)
    }

    private var registeredEngine: Int? = null

    fun engineAttached(engineId: Int) {
        if (ownership.attached(engineId) != EngineOwnership.Decision.TAKE) {
            return
        }
        port.log("owner take engine=$engineId")
        register(engineId)
    }

    fun activityAttached(engineId: Int) {
        when (ownership.activityAttached(engineId)) {
            EngineOwnership.Decision.KEEP -> Unit
            EngineOwnership.Decision.TRANSFER -> transferTo(engineId)
            else -> Unit
        }
    }

    fun engineDetached(engineId: Int) {
        when (ownership.detached(engineId)) {
            EngineOwnership.Decision.RELEASE -> {
                port.log("owner release engine=$engineId")
                release(engineId)
            }
            else -> Unit
        }
    }

    // The engine that shows the app owns the players, even when a headless engine attached first.
    private fun transferTo(engineId: Int) {
        val previousEngine = registeredEngine
        previousEngine?.let { cleanup(it) }
        register(engineId)
        port.log("owner transfer from=${previousEngine ?: "none"} to=$engineId")
    }

    private fun release(engineId: Int) {
        cleanup(engineId)
        port.publishOwner(null)
        port.log("owner publish engine=none")
    }

    private fun register(engineId: Int) {
        registeredEngine = engineId
        port.publishOwner(engineId)
        port.log("owner publish engine=$engineId")
        port.setMethodHandler(engineId)
        port.startHeadsetManager(engineId)
        port.registerFocusListener(engineId)
        port.registerMediaButtons(engineId)
    }

    private fun cleanup(engineId: Int) {
        port.abandonFocus(engineId)
        port.clearMethodHandler(engineId)
        port.stopHeadsetManager(engineId)
        port.unregisterFocusListener(engineId)
        port.stopPlayers(engineId)
        val cardOwnerIsCleanedEngine = port.cardOwnerEngine() == engineId
        if (cardOwnerIsCleanedEngine) port.closeCardService()
        port.log("cleanup engine=$engineId focus=abandoned card=${if (cardOwnerIsCleanedEngine) "closed" else "kept"}")
    }
}
