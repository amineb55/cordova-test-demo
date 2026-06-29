package com.actionverite.live.domain.model

/** Identity providers supported at sign-up (see spec: "Inscription"). */
enum class AuthProvider { GOOGLE, APPLE, FACEBOOK, EMAIL, PHONE, ANONYMOUS }

/** Coarse interest tags used by matchmaking and AI challenge personalization. */
enum class Interest {
    MUSIC, SPORTS, GAMING, MOVIES, TRAVEL, FOOD, ART, TECH,
    FASHION, FITNESS, BOOKS, SCIENCE, NATURE, PARTY, ROMANCE, COMEDY,
}

/**
 * A registered user. Personalization fields (language/country/birth/interests)
 * feed both matchmaking and the AI challenge generator, exactly as required by
 * the spec ("adapté à l'âge, langue, pays, historique, préférences et limites").
 *
 * Pure value type — no Android/Firebase types leak into the domain.
 */
data class UserProfile(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val providers: Set<AuthProvider> = emptySet(),
    val languageCode: String = "en",      // ISO-639-1
    val countryCode: String = "US",       // ISO-3166-1 alpha-2
    val birthEpochDay: Long? = null,      // days since 1970-01-01; null = undisclosed
    val gender: Gender = Gender.UNSPECIFIED,
    val interests: Set<Interest> = emptySet(),
    val limits: ContentLimits = ContentLimits.DEFAULT,
    val stats: PlayerStats = PlayerStats.EMPTY,
    val gold: Long = 0,                    // current Gold balance (server-authoritative)
    val reputation: Reputation = Reputation.NEW_ACCOUNT,
    val createdAtEpochMs: Long = 0L,
) {
    /**
     * Age in whole years on [todayEpochDay]. `today` is injected (never read from
     * a system clock here) so the calculation stays deterministic and testable.
     */
    fun ageOn(todayEpochDay: Long): Int? {
        val birth = birthEpochDay ?: return null
        if (todayEpochDay < birth) return 0
        // 365.2425 days per year accounts for leap years without a calendar dep.
        return ((todayEpochDay - birth) / 365.2425).toInt()
    }

    fun isAdultOn(todayEpochDay: Long): Boolean = (ageOn(todayEpochDay) ?: 0) >= 18
}

/**
 * Per-user content boundaries. Drives moderation/filtering before any challenge
 * is shown. "+18 optionnel" from the spec maps to [allowAdult].
 */
data class ContentLimits(
    val allowTruth: Boolean = true,
    val allowDare: Boolean = true,
    val allowAdult: Boolean = false,
    val maxDifficulty: Int = Difficulty.MAX,
    val blockedCategories: Set<ChallengeCategory> = emptySet(),
) {
    companion object {
        val DEFAULT = ContentLimits()

        /** Conservative limits applied automatically to minors. */
        val MINOR_SAFE = ContentLimits(
            allowAdult = false,
            maxDifficulty = 3,
            blockedCategories = setOf(ChallengeCategory.ADULT, ChallengeCategory.PHYSICAL),
        )
    }
}

/** Thematic category of a challenge; used for filtering and personalization. */
enum class ChallengeCategory {
    FUNNY, EMBARRASSING, DEEP, ROMANTIC, SOCIAL, PHYSICAL, CREATIVE, ADULT,
}
