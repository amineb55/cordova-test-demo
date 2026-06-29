package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.min

/**
 * Scores how compatible a candidate is with the seeker on a 0.0..1.0 scale
 * ("matchmaking intelligent"). The weighted components sum to 1.0:
 *
 *   language 0.30 · interests 0.25 · level 0.15 · age 0.10 · country 0.10 · ping 0.10
 *
 * Pure and deterministic, so the whole matchmaking ranking is unit-testable.
 */
class MatchmakingScorer @Inject constructor() {

    fun score(
        self: MatchProfile,
        candidate: MatchProfile,
        preferences: MatchPreferences = MatchPreferences(),
    ): Double {
        val language = if (self.languageCode == candidate.languageCode) 1.0 else 0.0
        val country = if (self.countryCode == candidate.countryCode) 1.0 else 0.0
        val interests = jaccard(self.interests, candidate.interests)
        val level = proximity(abs(self.level - candidate.level), preferences.maxLevelGap)
        val age = ageScore(self.age, candidate.age, preferences.maxAgeGap)
        val ping = 1.0 - min(1.0, candidate.pingMs.coerceAtLeast(0).toDouble() / preferences.maxPingMs)

        val score = W_LANGUAGE * language +
            W_INTERESTS * interests +
            W_LEVEL * level +
            W_AGE * age +
            W_COUNTRY * country +
            W_PING * ping
        return score.coerceIn(0.0, 1.0)
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
        const val W_LANGUAGE = 0.30
        const val W_INTERESTS = 0.25
        const val W_LEVEL = 0.15
        const val W_AGE = 0.10
        const val W_COUNTRY = 0.10
        const val W_PING = 0.10
    }
}
