package com.actionverite.live.domain.model

/** Ranking scopes required by the spec: "classement mondial, national et entre amis". */
enum class LeaderboardScope { GLOBAL, NATIONAL, FRIENDS }

data class LeaderboardEntry(
    val rank: Int,
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val level: Int,
    val xp: Long,
    val countryCode: String? = null,
    val isCurrentUser: Boolean = false,
)
