package com.actionverite.live.domain.model

/**
 * Reputation buckets used to prioritize matchmaking (spec "RÉPUTATION").
 * Ordered best-to-integrate first; [NEW] is intentionally ranked above
 * [FEW_RATINGS] so brand-new accounts get matched and earn their first ratings
 * ("doivent être régulièrement intégrés").
 */
enum class ReputationTier { EXCELLENT, GOOD, NEW, FEW_RATINGS }

/** Aggregated reputation for a user, derived from post-game peer ratings. */
data class Reputation(
    val averageScore: Double = 0.0, // 1.0..5.0, 0 when unrated
    val ratingsCount: Int = 0,
    val tier: ReputationTier = ReputationTier.NEW,
) {
    companion object {
        val NEW_ACCOUNT = Reputation()
    }
}

/** A single end-of-game rating one player gives another (1..5). */
data class PlayerRating(
    val raterUid: String,
    val targetUid: String,
    val score: Int,
) {
    init {
        require(score in MIN_SCORE..MAX_SCORE) { "Rating must be $MIN_SCORE..$MAX_SCORE, was $score" }
    }

    companion object {
        const val MIN_SCORE = 1
        const val MAX_SCORE = 5
    }
}

/** Server-configurable reputation thresholds (Firebase Remote Config). */
data class ReputationConfig(
    val minRatingsForTier: Int = 5,    // below this, a rated account is FEW_RATINGS
    val excellentThreshold: Double = 4.5,
    val goodThreshold: Double = 3.5,
) {
    companion object {
        val DEFAULT = ReputationConfig()
    }
}
