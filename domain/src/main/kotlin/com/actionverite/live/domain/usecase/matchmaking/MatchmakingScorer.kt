package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.ReputationTier
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

/**
 * Scores how compatible a candidate is with the seeker on a 0.0..1.0 scale
 * ("matchmaking intelligent"). Per the enhanced spec, **same country is the top
 * priority, then same language**, followed by interests and reputation. The
 * weighted components sum to 1.0:
 *
 *   country 0.30 · language 0.22 · interests 0.18 · reputation 0.12 ·
 *   level 0.08 · age 0.05 · ping 0.05
 *
 * Gender balancing is a group-formation concern handled in [FormMatchUseCase],
 * not a pairwise score. Pure and deterministic, so the ranking is unit-testable.
 */
class MatchmakingScorer @Inject constructor() {

    fun score(
        self: MatchProfile,
        candidate: MatchProfile,
        preferences: MatchPreferences = MatchPreferences(),
    ): Double {
        val country = if (self.countryCode == candidate.countryCode) 1.0 else 0.0
        val language = if (self.languageCode == candidate.languageCode) 1.0 else 0.0
        val interests = jaccard(self.interests, candidate.interests)
        val reputation = reputationValue(candidate.reputationTier)
        val level = proximity(abs(self.level - candidate.level), preferences.maxLevelGap)
        val age = ageScore(self.age, candidate.age, preferences.maxAgeGap)
        val ping = 1.0 - min(1.0, candidate.pingMs.coerceAtLeast(0).toDouble() / preferences.maxPingMs)

        val score = W_COUNTRY * country +
            W_LANGUAGE * language +
            W_INTERESTS * interests +
            W_REPUTATION * reputation +
            W_LEVEL * level +
            W_AGE * age +
            W_PING * ping
        return score.coerceIn(0.0, 1.0)
    }

    /**
     * Maps a reputation tier to a preference weight. [ReputationTier.NEW] is
     * boosted above [ReputationTier.FEW_RATINGS] so brand-new accounts are
     * regularly integrated to earn their first ratings, per the spec.
     */
    private fun reputationValue(tier: ReputationTier): Double = when (tier) {
        ReputationTier.EXCELLENT -> 1.0
        ReputationTier.GOOD -> 0.8
        ReputationTier.NEW -> 0.6
        ReputationTier.FEW_RATINGS -> 0.5
    }

    private fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        return if (union == 0.0) 0.0 else intersection / union
    }

    /** 1.0 when gap is 0, decaying linearly to 0.0 at [maxGap]. */
    private fun proximity(gap: Int, maxGap: Int): Double {
        if (maxGap <= 0) return if (gap == 0) 1.0 else 0.0
        return (1.0 - min(1.0, gap.toDouble() / maxGap)).coerceIn(0.0, 1.0)
    }

    /** Neutral 0.5 when either age is unknown, otherwise proximity-based. */
    private fun ageScore(selfAge: Int?, candidateAge: Int?, maxGap: Int): Double {
        if (selfAge == null || candidateAge == null) return 0.5
        return proximity(abs(selfAge - candidateAge), maxGap)
    }

    companion object {
        const val W_COUNTRY = 0.30
        const val W_LANGUAGE = 0.22
        const val W_INTERESTS = 0.18
        const val W_REPUTATION = 0.12
        const val W_LEVEL = 0.08
        const val W_AGE = 0.05
        const val W_PING = 0.05
    }
}
