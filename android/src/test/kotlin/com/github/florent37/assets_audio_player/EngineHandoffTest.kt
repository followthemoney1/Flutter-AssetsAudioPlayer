package com.github.florent37.assets_audio_player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val headlessEngine = 1
private const val uiEngine = 2

/** Simulated engine, media-button, published-owner and visible-card state. */
private class SimulatedPort : EngineHandoff.Port {
    val enginesWithHandler = mutableSetOf<Int>()
    val enginesWithHeadsetManager = mutableSetOf<Int>()
    val enginesWithFocusListener = mutableSetOf<Int>()
    val enginesHoldingFocusRequest = mutableSetOf<Int>()
    val runningPlayers = mutableMapOf<Int, MutableSet<String>>()
    var mediaButtonEngine: Int? = null
    var mediaButtonCallback: Any? = null
    val mediaButtonRegistrationCounts = mutableMapOf<Int, Int>()
    var publishedOwner: Int? = null
    var cardOwnerEngine: Int? = null
    var cardOwnerPlayerId: String? = null
    var cardVisible = false
    var actionResolvedEngine: Int? = null
    val logs = mutableListOf<String>()

    fun play(engineId: Int, playerId: String) {
        runningPlayers.getOrPut(engineId) { mutableSetOf() }.add(playerId)
    }

    // NotificationManager.kt resolves the card registration through the static owner.
    fun registerCardOwner(playerId: String) {
        val owner = publishedOwner ?: return
        cardOwnerEngine = owner
        cardOwnerPlayerId = playerId
        cardVisible = true
    }

    // NotificationService.kt resolves a notification action's player through the static owner.
    fun resolveNotificationAction(playerId: String) {
        val owner = publishedOwner ?: return
        actionResolvedEngine = if (runningPlayers[owner]?.contains(playerId) == true) owner else null
    }

    override fun publishOwner(engineId: Int?) {
        publishedOwner = engineId
    }

    override fun setMethodHandler(engineId: Int) {
        enginesWithHandler.add(engineId)
    }

    override fun clearMethodHandler(engineId: Int) {
        enginesWithHandler.remove(engineId)
    }

    override fun startHeadsetManager(engineId: Int) {
        enginesWithHeadsetManager.add(engineId)
    }

    override fun stopHeadsetManager(engineId: Int) {
        enginesWithHeadsetManager.remove(engineId)
    }

    override fun registerFocusListener(engineId: Int) {
        enginesWithFocusListener.add(engineId)
    }

    override fun unregisterFocusListener(engineId: Int) {
        enginesWithFocusListener.remove(engineId)
    }

    override fun registerMediaButtons(engineId: Int) {
        mediaButtonEngine = engineId
        mediaButtonCallback = Any()
        mediaButtonRegistrationCounts[engineId] = mediaButtonRegistrationCounts.getOrDefault(engineId, 0) + 1
    }

    override fun abandonFocus(engineId: Int) {
        enginesHoldingFocusRequest.remove(engineId)
    }

    override fun stopPlayers(engineId: Int) {
        runningPlayers[engineId]?.clear()
    }

    override fun cardOwnerEngine(): Int? = cardOwnerEngine

    override fun closeCardService() {
        cardVisible = false
    }

    override fun log(line: String) {
        logs.add(line)
    }
}

private class HandoffFixture {
    val port = SimulatedPort()
    val handoff = EngineHandoff(EngineOwnership(), port)

    fun attachUiEngineAndPlay() {
        handoff.engineAttached(uiEngine)
        handoff.activityAttached(uiEngine)
        port.play(uiEngine, "ui-player")
    }

    /** The full registered state of one engine, for unchanged-state assertions. */
    fun registrationSnapshot(engineId: Int): List<Any?> =
        listOf(
            port.enginesWithHandler.contains(engineId),
            port.enginesWithHeadsetManager.contains(engineId),
            port.enginesWithFocusListener.contains(engineId),
            port.runningPlayers[engineId].orEmpty().toList(),
            port.mediaButtonEngine,
            port.mediaButtonCallback,
            port.mediaButtonRegistrationCounts[engineId],
            port.publishedOwner,
            port.cardOwnerEngine,
            port.cardVisible,
        )
}

/** Wiring tests EO-1 to EO-7 for [EngineHandoff]; every assertion reads simulated state. */
class EngineHandoffTest {

    // EO-1: headless first, with a focus request held by the headless engine.
    @Test
    fun `EO-1 the UI activity attach moves everything off the headless engine to the UI engine`() {
        val fixture = HandoffFixture()
        fixture.handoff.engineAttached(headlessEngine)
        fixture.port.enginesHoldingFocusRequest.add(headlessEngine)
        fixture.port.play(headlessEngine, "headless-player")

        fixture.handoff.activityAttached(uiEngine)

        assertTrue(fixture.port.enginesWithHandler.contains(uiEngine))
        assertTrue(fixture.port.enginesWithHeadsetManager.contains(uiEngine))
        assertTrue(fixture.port.enginesWithFocusListener.contains(uiEngine))
        assertEquals(uiEngine, fixture.port.mediaButtonEngine)
        assertEquals(uiEngine, fixture.port.publishedOwner)
        assertFalse(fixture.port.enginesWithHandler.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithHeadsetManager.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithFocusListener.contains(headlessEngine))
        assertFalse(fixture.port.enginesHoldingFocusRequest.contains(headlessEngine))
        assertTrue(fixture.port.runningPlayers[headlessEngine].isNullOrEmpty())
    }

    // EO-2: UI first, a later headless engine registers nothing and changes nothing.
    @Test
    fun `EO-2 a later headless engine registers nothing and changes nothing`() {
        val fixture = HandoffFixture()
        fixture.attachUiEngineAndPlay()
        val before = fixture.registrationSnapshot(uiEngine)

        fixture.handoff.engineAttached(headlessEngine)

        assertEquals(before, fixture.registrationSnapshot(uiEngine))
        assertEquals(uiEngine, fixture.port.publishedOwner)
        assertFalse(fixture.port.enginesWithHandler.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithHeadsetManager.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithFocusListener.contains(headlessEngine))
        assertTrue(fixture.port.mediaButtonEngine != headlessEngine)
    }

    // EO-3: the old owner's detach after a transfer changes nothing of the new owner.
    @Test
    fun `EO-3 the old owner detach after a transfer leaves the new owner and its card untouched`() {
        val fixture = HandoffFixture()
        fixture.handoff.engineAttached(headlessEngine)
        fixture.port.play(headlessEngine, "headless-player")
        fixture.port.cardOwnerEngine = headlessEngine
        fixture.port.cardVisible = true
        fixture.handoff.activityAttached(uiEngine)
        fixture.port.cardOwnerEngine = uiEngine
        fixture.port.cardVisible = true
        val before = fixture.registrationSnapshot(uiEngine)

        fixture.handoff.engineDetached(headlessEngine)

        assertEquals(before, fixture.registrationSnapshot(uiEngine))
        assertEquals(uiEngine, fixture.port.publishedOwner)
        assertTrue(fixture.port.cardVisible)
    }

    // EO-4: an activity reattach on the owning engine changes nothing.
    @Test
    fun `EO-4 an activity reattach on the owning engine changes nothing`() {
        val fixture = HandoffFixture()
        fixture.handoff.engineAttached(uiEngine)
        val callbackAfterTake = fixture.port.mediaButtonCallback
        fixture.handoff.activityAttached(uiEngine)
        assertEquals(1, fixture.port.mediaButtonRegistrationCounts[uiEngine])
        assertSame(callbackAfterTake, fixture.port.mediaButtonCallback)

        fixture.port.play(uiEngine, "ui-player")
        fixture.port.cardOwnerEngine = uiEngine
        fixture.port.cardVisible = true
        val activeCardCallback = Any()
        fixture.port.mediaButtonCallback = activeCardCallback
        val before = fixture.registrationSnapshot(uiEngine)

        fixture.handoff.activityAttached(uiEngine)

        assertEquals(before, fixture.registrationSnapshot(uiEngine))
        assertSame(activeCardCallback, fixture.port.mediaButtonCallback)
        assertEquals(uiEngine, fixture.port.publishedOwner)
        assertTrue(fixture.port.cardVisible)
    }

    // EO-5: the owner's detach, with a focus request held.
    @Test
    fun `EO-5 the owner detach releases focus, clears its state, publishes no owner and closes its card`() {
        val fixture = HandoffFixture()
        fixture.handoff.engineAttached(headlessEngine)
        fixture.port.enginesHoldingFocusRequest.add(headlessEngine)
        fixture.port.play(headlessEngine, "headless-player")
        fixture.port.cardOwnerEngine = headlessEngine
        fixture.port.cardVisible = true

        fixture.handoff.engineDetached(headlessEngine)

        assertFalse(fixture.port.enginesHoldingFocusRequest.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithHandler.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithHeadsetManager.contains(headlessEngine))
        assertFalse(fixture.port.enginesWithFocusListener.contains(headlessEngine))
        assertTrue(fixture.port.runningPlayers[headlessEngine].isNullOrEmpty())
        assertNull(fixture.port.publishedOwner)
        assertFalse(fixture.port.cardVisible)
    }

    // EO-6: cleanup of an engine whose player does not own the card leaves the card visible.
    @Test
    fun `EO-6 the owner detach keeps the card when the record names another engine`() {
        val fixture = HandoffFixture()
        fixture.attachUiEngineAndPlay()
        fixture.port.cardVisible = true
        // The card record names another engine, so this engine's player does not own the visible card.
        fixture.port.cardOwnerEngine = headlessEngine

        fixture.handoff.engineDetached(uiEngine)

        assertTrue(fixture.port.cardVisible)
        assertNull(fixture.port.publishedOwner)
    }

    // EO-7: the published owner follows the decisions and the card and action paths resolve through it.
    @Test
    fun `EO-7 the published owner follows the decisions and resolves card and action paths`() {
        val fixture = HandoffFixture()
        fixture.handoff.engineAttached(headlessEngine)
        assertEquals(headlessEngine, fixture.port.publishedOwner)

        fixture.handoff.activityAttached(uiEngine)
        assertEquals(uiEngine, fixture.port.publishedOwner)
        fixture.port.play(uiEngine, "ui-player")
        fixture.port.registerCardOwner("ui-player")
        assertEquals(uiEngine, fixture.port.cardOwnerEngine)
        assertEquals("ui-player", fixture.port.cardOwnerPlayerId)
        fixture.port.resolveNotificationAction("ui-player")
        assertEquals(uiEngine, fixture.port.actionResolvedEngine)

        fixture.handoff.engineDetached(headlessEngine)
        assertEquals(uiEngine, fixture.port.publishedOwner)

        fixture.handoff.engineDetached(uiEngine)
        assertNull(fixture.port.publishedOwner)
    }
}
