package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.model.VideoGridLayout.Orientation
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ComputeVideoGridLayoutUseCaseTest {

    private val layout = ComputeVideoGridLayoutUseCase()

    @Test
    fun `portrait layouts are balanced for 2 to 6 players`() {
        assertThat(layout(2).let { it.rows to it.columns }).isEqualTo(2 to 1)
        assertThat(layout(3).let { it.rows to it.columns }).isEqualTo(2 to 2)
        assertThat(layout(4).let { it.rows to it.columns }).isEqualTo(2 to 2)
        assertThat(layout(5).let { it.rows to it.columns }).isEqualTo(3 to 2)
        assertThat(layout(6).let { it.rows to it.columns }).isEqualTo(3 to 2)
    }

    @Test
    fun `landscape layouts favour columns`() {
        val l = Orientation.LANDSCAPE
        assertThat(layout(2, l).let { it.rows to it.columns }).isEqualTo(1 to 2)
        assertThat(layout(3, l).let { it.rows to it.columns }).isEqualTo(1 to 3)
        assertThat(layout(4, l).let { it.rows to it.columns }).isEqualTo(2 to 2)
        assertThat(layout(5, l).let { it.rows to it.columns }).isEqualTo(2 to 3)
        assertThat(layout(6, l).let { it.rows to it.columns }).isEqualTo(2 to 3)
    }

    @Test
    fun `every layout has enough cells and no fully-empty row`() {
        for (orientation in Orientation.entries) {
            for (n in 2..6) {
                val grid = layout(n, orientation)
                assertThat(grid.cells).isAtLeast(n)
                // The last row must contain at least one tile.
                assertThat(grid.cells - n).isLessThan(grid.columns)
            }
        }
    }

    @Test
    fun `tilesInRow handles a short final row`() {
        val grid = layout(5) // portrait 3x2
        assertThat(grid.tilesInRow(0)).isEqualTo(2)
        assertThat(grid.tilesInRow(1)).isEqualTo(2)
        assertThat(grid.tilesInRow(2)).isEqualTo(1)
    }

    @Test
    fun `rejects player counts outside 2 to 6`() {
        assertThrows(IllegalArgumentException::class.java) { layout(1) }
        assertThrows(IllegalArgumentException::class.java) { layout(7) }
    }
}
