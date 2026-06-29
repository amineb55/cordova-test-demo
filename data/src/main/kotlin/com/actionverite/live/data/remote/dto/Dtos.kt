package com.actionverite.live.data.remote.dto

/**
 * Firestore data-transfer objects. Every field has a default value so Firestore's
 * reflective deserialization can use the synthesized no-arg constructor. These
 * types never leave the data layer — repositories map them to domain models.
 */

data class UserDto(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val email: String? = null,
    val phoneNumber: String? = null,
    val providers: List<String> = emptyList(),
    val languageCode: String = "en",
    val countryCode: String = "US",
    val birthEpochDay: Long? = null,
    val interests: List<String> = emptyList(),
    val limits: LimitsDto = LimitsDto(),
    val stats: StatsDto = StatsDto(),
    val createdAtEpochMs: Long = 0L,
)

data class LimitsDto(
    val allowTruth: Boolean = true,
    val allowDare: Boolean = true,
    val allowAdult: Boolean = false,
    val maxDifficulty: Int = 5,
    val blockedCategories: List<String> = emptyList(),
)

data class StatsDto(
    val xp: Long = 0,
    val level: Int = 1,
    val gamesPlayed: Int = 0,
    val truthsAnswered: Int = 0,
    val daresCompleted: Int = 0,
    val daresFailed: Int = 0,
    val votesCast: Int = 0,
    val friendsCount: Int = 0,
    val maxDifficultyCompleted: Int = 0,
    val unlockedBadges: List<String> = emptyList(),
)

data class PlayerDto(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val level: Int = 1,
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val speaking: Boolean = false,
    val pingMs: Int = 0,
    val connection: String = "GOOD",
    val isHost: Boolean = false,
    val joinedAtEpochMs: Long = 0L,
)

data class RoomDto(
    val id: String = "",
    val hostUid: String = "",
    val visibility: String = "PUBLIC",
    val status: String = "WAITING",
    val players: List<PlayerDto> = emptyList(),
    val languageCode: String = "en",
    val countryCode: String? = null,
    val adultOnly: Boolean = false,
    val inviteCode: String? = null,
    val maxPlayers: Int = 6,
    val createdAtEpochMs: Long = 0L,
)

data class DifficultyVoteDto(
    val voterUid: String = "",
    val value: Int = 3,
)

data class DifficultyResultDto(
    val average: Double = 3.0,
    val target: Int = 3,
    val voteCount: Int = 0,
)

data class ChallengeDto(
    val id: String = "",
    val type: String = "TRUTH",
    val text: String = "",
    val category: String = "FUNNY",
    val difficulty: Int = 1,
    val languageCode: String = "en",
    val isAdult: Boolean = false,
    val signature: String = "",
    val generatedAtEpochMs: Long = 0L,
)

data class TurnDto(
    val index: Int = 0,
    val activeUid: String = "",
    val type: String? = null,
    val votes: List<DifficultyVoteDto> = emptyList(),
    val difficulty: DifficultyResultDto? = null,
    val challenge: ChallengeDto? = null,
    val outcome: String = "PENDING",
)

data class GameSessionDto(
    val roomId: String = "",
    val phase: String = "LOBBY",
    val turnOrder: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val round: Int = 0,
    val maxRounds: Int = 10,
    val currentTurn: TurnDto? = null,
    val history: List<TurnDto> = emptyList(),
    val startedAtEpochMs: Long = 0L,
)

data class FriendDto(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val level: Int = 1,
    val status: String = "ACCEPTED",
    val presence: String = "OFFLINE",
    val countryCode: String? = null,
)

data class FriendRequestDto(
    val id: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    val fromDisplayName: String = "",
    val createdAtEpochMs: Long = 0L,
)

data class ChatMessageDto(
    val id: String = "",
    val senderUid: String = "",
    val senderName: String = "",
    val text: String = "",
    val sentAtEpochMs: Long = 0L,
    val moderated: Boolean = false,
)

data class LeaderboardEntryDto(
    val uid: String = "",
    val displayName: String = "",
    val photoUrl: String? = null,
    val level: Int = 1,
    val xp: Long = 0,
    val countryCode: String? = null,
)

data class ReportDto(
    val id: String = "",
    val reporterUid: String = "",
    val targetUid: String = "",
    val roomId: String? = null,
    val reason: String = "OTHER",
    val details: String? = null,
    val createdAtEpochMs: Long = 0L,
)
