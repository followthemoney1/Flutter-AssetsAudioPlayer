package com.github.florent37.assets_audio_player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Decision-level tests for [EngineOwnership]; the wiring tests live in EngineHandoffTest. */
class EngineOwnershipTest {

    @Test
    fun `attached with no owner takes ownership`() {
        val ownership = EngineOwnership()

        val decision = ownership.attached(1)

        assertEquals(EngineOwnership.Decision.TAKE, decision)
        assertEquals(1, ownership.owner)
    }

    @Test
    fun `attached while an engine already owns waits and keeps that owner`() {
        val ownership = EngineOwnership()
        ownership.attached(1)

        val decision = ownership.attached(2)

        assertEquals(EngineOwnership.Decision.WAIT, decision)
        assertEquals(1, ownership.owner)
    }

    @Test
    fun `activity attached on the owning engine keeps it`() {
        val ownership = EngineOwnership()
        ownership.attached(1)

        val decision = ownership.activityAttached(1)

        assertEquals(EngineOwnership.Decision.KEEP, decision)
        assertEquals(1, ownership.owner)
    }

    @Test
    fun `activity attached on another engine transfers ownership to it`() {
        val ownership = EngineOwnership()
        ownership.attached(1)

        val decision = ownership.activityAttached(2)

        assertEquals(EngineOwnership.Decision.TRANSFER, decision)
        assertEquals(2, ownership.owner)
    }

    @Test
    fun `detached owner releases and clears the owner`() {
        val ownership = EngineOwnership()
        ownership.attached(1)

        val decision = ownership.detached(1)

        assertEquals(EngineOwnership.Decision.RELEASE, decision)
        assertEquals(null, ownership.owner)
    }

    @Test
    fun `detached engine that is not the owner is ignored`() {
        val ownership = EngineOwnership()
        ownership.attached(1)

        val decision = ownership.detached(2)

        assertEquals(EngineOwnership.Decision.IGNORE, decision)
        assertEquals(1, ownership.owner)
    }
}
