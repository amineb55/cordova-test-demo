package com.actionverite.live.domain.model

/**
 * The compact set of signals matchmaking scores on. Decoupled from
 * [UserProfile] so the scorer stays small and trivially testable.
 */
data class MatchProfile(
    val uid: String,
    val languageCode: String,
    val countryCode: String,
    val age: Int?,
    val level: Int,
    val interests: Set<Interest>,
    val allowAdult: Boolean,
    val pingMs: Int = 0,
    val gender: Gender = Gender.UNSPECIFIED,
    val reputationTier: ReputationTier = ReputationTier.NEW,
)

/** Hard filters + soft preferences for a matchmaking search. */
data class MatchPreferences(
    val desiredSize: Int = Room.MIN_PLAYERS,
    val sameLanguageOnly: Boolean = true,
    val sameCountryOnly: Boolean = false,
    val adultOnly: Boolean = false,
    val maxLevelGap: Int = 10,
    val maxAgeGap: Int = 8,
    val maxPingMs: Int = 300,
    val balanceGender: Boolean = true,
) {
    init {
        require(desiredSize in Room.MIN_PLAYERS..Room.MAX_PLAYERS) {
            "desiredSize must be in ${Room.MIN_PLAYERS}..${Room.MAX_PLAYERS}"
        }
    }
}

/** A scored candidate produced by the matchmaking scorer. */
data class ScoredCandidate(
    val profile: MatchProfile,
    val score: Double,
)

/** The result of forming a match: the chosen group and its average compatibility. */
data class MatchResult(
    val members: List<MatchProfile>,
    val averageScore: Double,
) {
    val size: Int get() = members.size
}
