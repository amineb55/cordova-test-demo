package com.actionverite.live.domain.usecase.game

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TurnRotationTest {

    private val order = listOf("a", "b", "c")
    private val all = order.toSet()

    @Test
    fun `advances to the next index without wrapping`() {
        val result = TurnRotation.advance(order, fromIndex = 0, present = all)
        assertThat(result.index).isEqualTo(1)
        assertThat(result.wrapped).isFalse()
    }

    @Test
    fun `wraps to the first index after the last player`() {
        val result = TurnRotation.advance(order, fromIndex = 2, present = all)
        assertThat(result.index).isEqualTo(0)
        assertThat(result.wrapped).isTrue()
    }

    @Test
    fun `skips disconnected players`() {
        // b is absent: a -> c (no wrap)
        val result = TurnRotation.advance(order, fromIndex = 0, present = setOf("a", "c"))
        assertThat(result.index).isEqualTo(2)
        assertThat(result.wrapped).isFalse()
    }

    @Test
    fun `wraps while skipping absent players`() {
        // from c, only a present besides -> wrap to a
        val result = TurnRotation.advance(order, fromIndex = 2, present = setOf("a", "c"))
        assertThat(result.index).isEqualTo(0)
        assertThat(result.wrapped).isTrue()
    }

    @Test
    fun `returns same index when nobody else is present`() {
        val result = TurnRotation.advance(order, fromIndex = 1, present = setOf("zzz"))
        assertThat(result.index).isEqualTo(1)
        assertThat(result.wrapped).isFalse()
    }

    @Test
    fun `empty order is handled`() {
        val result = TurnRotation.advance(emptyList(), fromIndex = 0, present = emptySet())
        assertThat(result.index).isEqualTo(0)
        assertThat(result.wrapped).isFalse()
    }
}
