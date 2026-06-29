package com.actionverite.live.domain.model

/** The core choice each turn: Vérité (truth) or Action (dare). */
enum class ChallengeType { TRUTH, DARE }

/**
 * Difficulty is a 1..5 scale. Only DARE turns are rated: "les autres votent une
 * difficulté de 1 à 5". TRUTH has no difficulty vote.
 */
object Difficulty {
    const val MIN = 1
    const val MAX = 5
    val RANGE = MIN..MAX

    fun isValid(value: Int): Boolean = value in RANGE
    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/** A single difficulty vote cast by another player for the active player's dare. */
data class DifficultyVote(
    val voterUid: String,
    val value: Int,
) {
    init {
        require(Difficulty.isValid(value)) { "Difficulty vote must be in ${Difficulty.RANGE}, was $value" }
    }
}

/**
 * Aggregated outcome of the difficulty vote. [average] is the exact mean fed to
 * the AI ("la moyenne est calculée puis l'IA génère une action correspondant
 * exactement à cette difficulté"); [target] is the clamped, rounded integer used
 * for display and for filtering against per-user [ContentLimits.maxDifficulty].
 */
data class DifficultyResult(
    val average: Double,
    val target: Int,
    val voteCount: Int,
) {
    companion object {
        /** Used when a dare has no eligible voters (solo edge / all abstained). */
        val NEUTRAL = DifficultyResult(average = 3.0, target = 3, voteCount = 0)
    }
}

/**
 * A generated challenge returned by the AI service. [signature] is a stable hash
 * used by history/anti-repeat logic so a user never sees the same prompt twice.
 */
data class Challenge(
    val id: String,
    val type: ChallengeType,
    val text: String,
    val category: ChallengeCategory,
    val difficulty: Int = Difficulty.MIN,
    val languageCode: String = "en",
    val isAdult: Boolean = false,
    val signature: String = "",
    val generatedAtEpochMs: Long = 0L,
)

/**
 * Everything the AI needs to generate a unique, personalized challenge. Mirrors
 * the spec's IA requirements (age, language, country, history, preferences,
 * limits) and is built by the domain, never by the UI.
 */
data class ChallengeRequest(
    val type: ChallengeType,
    val targetUid: String,
    val languageCode: String,
    val countryCode: String,
    val age: Int?,
    val interests: Set<Interest>,
    val limits: ContentLimits,
    val difficulty: DifficultyResult? = null, // null for TRUTH
    val recentSignatures: List<String> = emptyList(),
)
