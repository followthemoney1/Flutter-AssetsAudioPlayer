package com.github.florent37.assets_audio_player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Direct tests of the pure bound: one generation per prepare, released only by its owner. */
class PreparationBoundTest {

    @Test
    fun `prepare arms a fresh generation`() {
        val bound = PreparationBound()
        val first = bound.prepare()
        assertTrue(bound.mayStart(first))
        val second = bound.prepare()
        assertTrue(second != first)
        assertFalse(bound.mayStart(first))
        assertTrue(bound.mayStart(second))
    }

    @Test
    fun `playbackStarted disarms expiry but keeps the generation current`() {
        val bound = PreparationBound()
        val gen = bound.prepare()
        bound.playbackStarted(gen)
        assertTrue(bound.mayStart(gen))
        assertFalse(bound.expire(gen))
    }

    @Test
    fun `terminal kills the generation`() {
        val bound = PreparationBound()
        val gen = bound.prepare()
        bound.terminal(gen)
        assertFalse(bound.mayStart(gen))
        assertFalse(bound.expire(gen))
        bound.playbackStarted(gen)
        assertFalse(bound.mayStart(gen))
    }

    @Test
    fun `expire consumes the bound`() {
        val bound = PreparationBound()
        val gen = bound.prepare()
        assertTrue(bound.expire(gen))
        assertFalse(bound.expire(gen))
        assertFalse(bound.mayStart(gen))
    }

    @Test
    fun `a foreign generation is ignored`() {
        val bound = PreparationBound()
        bound.prepare()
        val current = bound.prepare()
        bound.playbackStarted(current - 1)
        bound.terminal(current - 1)
        assertFalse(bound.expire(current - 1))
        assertTrue(bound.mayStart(current))
    }
}
