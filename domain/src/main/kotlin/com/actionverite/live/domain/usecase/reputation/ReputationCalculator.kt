package com.actionverite.live.domain.usecase.reputation

import com.actionverite.live.domain.model.PlayerRating
import com.actionverite.live.domain.model.Reputation
import com.actionverite.live.domain.model.ReputationConfig
import com.actionverite.live.domain.model.ReputationTier
import javax.inject.Inject

/**
 * Pure reputation math (spec "RÉPUTATION"). Maps an average peer rating + count
 * to a [ReputationTier] and incrementally folds a new rating into a running
 * average. Thresholds are server-configurable via [ReputationConfig].
 */
class ReputationCalculator @Inject constructor() {

    /**
     * Classifies a reputation into one of the four tiers:
     *  - [ReputationTier.NEW]: never rated,
     *  - [ReputationTier.FEW_RATINGS]: rated but below the confidence threshold,
     *  - [ReputationTier.EXCELLENT]: enough ratings and a high average,
     *  - [ReputationTier.GOOD]: enough ratings, anything below excellent.
     */
    fun tierFor(
        averageScore: Double,
        ratingsCount: Int,
        config: ReputationConfig = ReputationConfig.DEFAULT,
    ): ReputationTier = when {
        ratingsCount <= 0 -> ReputationTier.NEW
        ratingsCount < config.minRatingsForTier -> ReputationTier.FEW_RATINGS
        averageScore >= config.excellentThreshold -> ReputationTier.EXCELLENT
        else -> ReputationTier.GOOD
    }

    /** Recomputes the tier for an existing [Reputation] (e.g. after a config change). */
    fun withTier(reputation: Reputation, config: ReputationConfig = ReputationConfig.DEFAULT): Reputation =
        reputation.copy(tier = tierFor(reputation.averageScore, reputation.ratingsCount, config))

    /** Folds a new 1..5 [score] into a running-average [Reputation]. */
    fun applyRating(
        current: Reputation,
        score: Int,
        config: ReputationConfig = ReputationConfig.DEFAULT,
    ): Reputation {
        require(score in PlayerRating.MIN_SCORE..PlayerRating.MAX_SCORE) {
            "Rating must be ${PlayerRating.MIN_SCORE}..${PlayerRating.MAX_SCORE}"
        }
        val newCount = current.ratingsCount + 1
        val newAverage = (current.averageScore * current.ratingsCount + score) / newCount
        return Reputation(
            averageScore = newAverage,
            ratingsCount = newCount,
            tier = tierFor(newAverage, newCount, config),
        )
    }
}
