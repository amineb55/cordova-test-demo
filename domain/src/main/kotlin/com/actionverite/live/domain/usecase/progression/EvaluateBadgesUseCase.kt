package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.BadgeId
import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.PlayerStats
import javax.inject.Inject

/**
 * Determines which badges a player qualifies for given their [PlayerStats], and
 * returns only the *newly* earned ones (those not already in
 * [PlayerStats.unlockedBadges]). Time-of-day badges (e.g. NIGHT_OWL) are awarded
 * server-side and are intentionally out of scope here.
 */
class EvaluateBadgesUseCase @Inject constructor() {

    /** Badges the player currently qualifies for, regardless of prior unlocks. */
    fun qualifiedBadges(stats: PlayerStats): Set<BadgeId> = buildSet {
        if (stats.gamesPlayed >= 1) add(BadgeId.FIRST_GAME)
        if (stats.gamesPlayed >= 50) add(BadgeId.GLOBETROTTER)
        if (stats.truthsAnswered >= 25) add(BadgeId.TRUTH_SEEKER)
        if (stats.daresCompleted >= 25) add(BadgeId.DAREDEVIL)
        if (stats.maxDifficultyCompleted >= Difficulty.MAX) add(BadgeId.FEARLESS)
        if (stats.friendsCount >= 10) add(BadgeId.SOCIAL_BUTTERFLY)
        if (stats.votesCast >= 100) add(BadgeId.PERFECT_VOTER)
        if (stats.level >= 10) add(BadgeId.LEVEL_10)
        if (stats.level >= 25) add(BadgeId.LEVEL_25)
        if (stats.level >= 50) add(BadgeId.LEVEL_50)
    }

    /** Badges earned right now that the player did not already have. */
    operator fun invoke(stats: PlayerStats): Set<BadgeId> =
        qualifiedBadges(stats) - stats.unlockedBadges
}
