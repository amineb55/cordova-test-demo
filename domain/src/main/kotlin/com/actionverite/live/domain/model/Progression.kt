package com.actionverite.live.domain.model

/**
 * Snapshot of a player's progression and lifetime stats. Drives XP, levels,
 * badges and leaderboards (spec: "Progression").
 */
data class PlayerStats(
    val xp: Long = 0,
    val level: Int = 1,
    val gamesPlayed: Int = 0,
    val truthsAnswered: Int = 0,
    val daresCompleted: Int = 0,
    val daresFailed: Int = 0,
    val votesCast: Int = 0,
    val friendsCount: Int = 0,
    val maxDifficultyCompleted: Int = 0,
    val unlockedBadges: Set<BadgeId> = emptySet(),
) {
    companion object {
        val EMPTY = PlayerStats()
    }
}

/** Computed view of where a player sits within their current level. */
data class LevelProgress(
    val level: Int,
    val currentLevelXp: Long,   // XP accumulated within the current level
    val xpForNextLevel: Long,   // XP span of the current level
    val totalXp: Long,
) {
    /** 0f..1f progress toward the next level, safe against divide-by-zero. */
    val fraction: Float
        get() = if (xpForNextLevel <= 0) 1f
        else (currentLevelXp.toFloat() / xpForNextLevel.toFloat()).coerceIn(0f, 1f)
}

/** Stable identifiers for achievements. */
enum class BadgeId {
    FIRST_GAME, SOCIAL_BUTTERFLY, TRUTH_SEEKER, DAREDEVIL, FEARLESS,
    LEVEL_10, LEVEL_25, LEVEL_50, GLOBETROTTER, PERFECT_VOTER, NIGHT_OWL,
}

data class Badge(
    val id: BadgeId,
    val title: String,
    val description: String,
    val tier: Tier = Tier.BRONZE,
) {
    enum class Tier { BRONZE, SILVER, GOLD, PLATINUM }
}

/** Reasons XP is awarded; each carries a base value used by the level system. */
enum class XpReason(val baseXp: Int) {
    TRUTH_ANSWERED(20),
    DARE_COMPLETED(30),       // scaled by difficulty
    DARE_FAILED(5),
    VOTE_CAST(2),
    GAME_FINISHED(15),
    DAILY_LOGIN(10),
    FRIEND_ADDED(5),
}

/** A computed XP grant, ready to be persisted. */
data class XpAward(
    val reason: XpReason,
    val amount: Int,
    val multiplier: Float = 1f,
) {
    val total: Int get() = (amount * multiplier).toInt()
}
