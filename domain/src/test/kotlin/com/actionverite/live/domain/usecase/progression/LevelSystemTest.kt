package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.PlayerStats
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LevelSystemTest {

    private val levels = LevelSystem()

    @Test
    fun `xp thresholds follow the documented curve`() {
        assertThat(levels.xpToReach(1)).isEqualTo(0)
        assertThat(levels.xpToReach(2)).isEqualTo(100)
        assertThat(levels.xpToReach(3)).isEqualTo(250)
        assertThat(levels.xpToReach(4)).isEqualTo(450)
        assertThat(levels.xpToReach(5)).isEqualTo(700)
        assertThat(levels.xpToReach(6)).isEqualTo(1000)
    }

    @Test
    fun `level for xp respects thresholds`() {
        assertThat(levels.levelForXp(0)).isEqualTo(1)
        assertThat(levels.levelForXp(99)).isEqualTo(1)
        assertThat(levels.levelForXp(100)).isEqualTo(2)
        assertThat(levels.levelForXp(249)).isEqualTo(2)
        assertThat(levels.levelForXp(250)).isEqualTo(3)
        assertThat(levels.levelForXp(999)).isEqualTo(5)
        assertThat(levels.levelForXp(1000)).isEqualTo(6)
    }

    @Test
    fun `level inversion is consistent with thresholds across many levels`() {
        for (level in 2..LevelSystem.MAX_LEVEL) {
            val threshold = levels.xpToReach(level)
            assertThat(levels.levelForXp(threshold)).isEqualTo(level)
            assertThat(levels.levelForXp(threshold - 1)).isEqualTo(level - 1)
        }
    }

    @Test
    fun `progress reports fraction within the current level`() {
        val progress = levels.progressFor(150) // level 2: [100, 250)
        assertThat(progress.level).isEqualTo(2)
        assertThat(progress.currentLevelXp).isEqualTo(50)
        assertThat(progress.xpForNextLevel).isEqualTo(150)
        assertThat(progress.fraction).isWithin(1e-6f).of(50f / 150f)
    }

    @Test
    fun `progress at level start is zero fraction`() {
        val progress = levels.progressFor(0)
        assertThat(progress.level).isEqualTo(1)
        assertThat(progress.fraction).isEqualTo(0f)
    }

    @Test
    fun `level is capped and saturates fraction at max`() {
        val huge = 100_000_000L
        assertThat(levels.levelForXp(huge)).isEqualTo(LevelSystem.MAX_LEVEL)
        val progress = levels.progressFor(huge)
        assertThat(progress.level).isEqualTo(LevelSystem.MAX_LEVEL)
        assertThat(progress.fraction).isEqualTo(1f)
    }

    @Test
    fun `applyXp adds xp and recomputes level, never going negative`() {
        val afterGain = levels.applyXp(PlayerStats.EMPTY, 100)
        assertThat(afterGain.xp).isEqualTo(100)
        assertThat(afterGain.level).isEqualTo(2)

        val afterNegative = levels.applyXp(afterGain, -50)
        assertThat(afterNegative.xp).isEqualTo(100) // negative deltas are ignored
    }
}
