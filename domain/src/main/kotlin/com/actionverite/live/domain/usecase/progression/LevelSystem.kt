package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.LevelProgress
import com.actionverite.live.domain.model.PlayerStats
import javax.inject.Inject
import kotlin.math.sqrt

/**
 * Deterministic XP ↔ level mapping.
 *
 * The XP required to *reach* a level follows a smooth quadratic curve so each
 * level costs a bit more than the last:
 *
 *   xpToReach(L) = 25 · (L − 1) · (L + 2)
 *
 * giving cumulative thresholds 0, 100, 250, 450, 700, 1000, … (level 1 starts at
 * 0 XP). The mapping is closed-form in both directions, so it stays O(1) even for
 * the millions of users the spec targets.
 */
class LevelSystem @Inject constructor() {

    /** Total XP needed to be exactly at the start of [level] (level >= 1). */
    fun xpToReach(level: Int): Long {
        val l = level.coerceAtLeast(1).toLong()
        return 25L * (l - 1L) * (l + 2L)
    }

    /** The level a player with [totalXp] currently holds (capped at [MAX_LEVEL]). */
    fun levelForXp(totalXp: Long): Int {
        if (totalXp <= 0L) return 1
        // Invert xpToReach: solve 25(L-1)(L+2) <= xp  →  L = floor((-1 + sqrt(1 + 4*(2 + xp/25)))/2)
        val approx = ((-1.0 + sqrt(1.0 + 4.0 * (2.0 + totalXp / 25.0))) / 2.0).toInt()
        var level = approx.coerceAtLeast(1)
        // Correct for any floating-point drift at the boundary.
        while (xpToReach(level + 1) <= totalXp) level++
        while (level > 1 && xpToReach(level) > totalXp) level--
        return level.coerceAtMost(MAX_LEVEL)
    }

    /** Rich progress view (current/next thresholds + fraction) for [totalXp]. */
    fun progressFor(totalXp: Long): LevelProgress {
        val safeXp = totalXp.coerceAtLeast(0L)
        val level = levelForXp(safeXp)
        if (level >= MAX_LEVEL) {
            return LevelProgress(MAX_LEVEL, 0, 0, safeXp)
        }
        val base = xpToReach(level)
        val next = xpToReach(level + 1)
        return LevelProgress(
            level = level,
            currentLevelXp = safeXp - base,
            xpForNextLevel = next - base,
            totalXp = safeXp,
        )
    }

    /** Adds [deltaXp] to [stats], recomputing the level. Never decreases XP. */
    fun applyXp(stats: PlayerStats, deltaXp: Int): PlayerStats {
        val newXp = (stats.xp + deltaXp.coerceAtLeast(0)).coerceAtLeast(0L)
        return stats.copy(xp = newXp, level = levelForXp(newXp))
    }

    companion object {
        const val MAX_LEVEL = 100
    }
}
