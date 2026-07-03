package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.BadgeId
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.PlayerStats
import com.actionverite.live.domain.model.RoundOutcome
import com.actionverite.live.domain.model.XpAward
import com.actionverite.live.domain.model.XpReason
import javax.inject.Inject
import kotlin.math.max

/** The full set of changes to apply after one player's turn resolves. */
data class TurnRewards(
    val stats: PlayerStats,
    val award: XpAward?,
    val newBadges: Set<BadgeId>,
)

/**
 * Pure aggregation of all progression effects of a resolved turn: updates the
 * lifetime counters, grants XP (difficulty-scaled for dares), recomputes the
 * level, and unlocks any newly earned badges. Deterministic and unit-testable.
 */
class ApplyTurnRewardsUseCase @Inject constructor(
    private val awardXp: AwardXpUseCase,
    private val levelSystem: LevelSystem,
    private val evaluateBadges: EvaluateBadgesUseCase,
) {

    operator fun invoke(
        stats: PlayerStats,
        type: ChallengeType,
        outcome: RoundOutcome,
        difficultyTarget: Int? = null,
    ): TurnRewards {
        // 1) Update lifetime counters based on what happened.
        val counted = when {
            type == ChallengeType.TRUTH && outcome == RoundOutcome.COMPLETED ->
                stats.copy(truthsAnswered = stats.truthsAnswered + 1)

            type == ChallengeType.DARE && outcome == RoundOutcome.COMPLETED ->
                stats.copy(
                    daresCompleted = stats.daresCompleted + 1,
                    maxDifficultyCompleted = max(stats.maxDifficultyCompleted, difficultyTarget ?: 0),
                )

            type == ChallengeType.DARE && outcome == RoundOutcome.FAILED ->
                stats.copy(daresFailed = stats.daresFailed + 1)

            else -> stats
        }

        // 2) Compute XP for the outcome (null when nothing is rewarded).
        val award: XpAward? = when {
            type == ChallengeType.TRUTH && outcome == RoundOutcome.COMPLETED ->
                awardXp(XpReason.TRUTH_ANSWERED)

            type == ChallengeType.DARE && outcome == RoundOutcome.COMPLETED ->
                awardXp(XpReason.DARE_COMPLETED, difficultyTarget)

            type == ChallengeType.DARE && outcome == RoundOutcome.FAILED ->
                awardXp(XpReason.DARE_FAILED)

            else -> null
        }

        // 3) Apply XP + level, then unlock badges.
        val leveled = if (award != null) levelSystem.applyXp(counted, award.total) else counted
        val newBadges = evaluateBadges(leveled)
        val finalStats = leveled.copy(unlockedBadges = leveled.unlockedBadges + newBadges)

        return TurnRewards(stats = finalStats, award = award, newBadges = newBadges)
    }
}
