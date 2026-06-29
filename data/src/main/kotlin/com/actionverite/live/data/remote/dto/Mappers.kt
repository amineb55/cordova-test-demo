package com.actionverite.live.data.remote.dto

import com.actionverite.live.domain.model.AuthProvider
import com.actionverite.live.domain.model.BadgeId
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeCategory
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.ConnectionQuality
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.DifficultyResult
import com.actionverite.live.domain.model.DifficultyVote
import com.actionverite.live.domain.model.Friend
import com.actionverite.live.domain.model.FriendRequest
import com.actionverite.live.domain.model.FriendStatus
import com.actionverite.live.domain.model.GamePhase
import com.actionverite.live.domain.model.Gender
import com.actionverite.live.domain.model.GameSession
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.ChatMessage
import com.actionverite.live.domain.model.Player
import com.actionverite.live.domain.model.PlayerStats
import com.actionverite.live.domain.model.Presence
import com.actionverite.live.domain.model.Reputation
import com.actionverite.live.domain.model.ReputationTier
import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.model.RoomStatus
import com.actionverite.live.domain.model.RoomVisibility
import com.actionverite.live.domain.model.RoundOutcome
import com.actionverite.live.domain.model.Turn
import com.actionverite.live.domain.model.UserProfile

/** Null-safe enum parsing: unknown/legacy values fall back to [default]. */
private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    this?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

// ---- User ----

fun UserDto.toDomain() = UserProfile(
    uid = uid,
    displayName = displayName,
    photoUrl = photoUrl,
    email = email,
    phoneNumber = phoneNumber,
    providers = providers.mapNotNull { runCatching { AuthProvider.valueOf(it) }.getOrNull() }.toSet(),
    languageCode = languageCode,
    countryCode = countryCode,
    birthEpochDay = birthEpochDay,
    gender = gender.toEnum(Gender.UNSPECIFIED),
    interests = interests.mapNotNull { runCatching { Interest.valueOf(it) }.getOrNull() }.toSet(),
    limits = limits.toDomain(),
    stats = stats.toDomain(),
    gold = gold,
    reputation = reputation.toDomain(),
    createdAtEpochMs = createdAtEpochMs,
)

fun UserProfile.toDto() = UserDto(
    uid = uid,
    displayName = displayName,
    photoUrl = photoUrl,
    email = email,
    phoneNumber = phoneNumber,
    providers = providers.map { it.name },
    languageCode = languageCode,
    countryCode = countryCode,
    birthEpochDay = birthEpochDay,
    gender = gender.name,
    interests = interests.map { it.name },
    limits = limits.toDto(),
    stats = stats.toDto(),
    gold = gold,
    reputation = reputation.toDto(),
    createdAtEpochMs = createdAtEpochMs,
)

fun ReputationDto.toDomain() = Reputation(
    averageScore = averageScore,
    ratingsCount = ratingsCount,
    tier = tier.toEnum(ReputationTier.NEW),
)

fun Reputation.toDto() = ReputationDto(
    averageScore = averageScore,
    ratingsCount = ratingsCount,
    tier = tier.name,
)

fun LimitsDto.toDomain() = ContentLimits(
    allowTruth = allowTruth,
    allowDare = allowDare,
    allowAdult = allowAdult,
    maxDifficulty = maxDifficulty,
    blockedCategories = blockedCategories.mapNotNull { runCatching { ChallengeCategory.valueOf(it) }.getOrNull() }.toSet(),
)

fun ContentLimits.toDto() = LimitsDto(
    allowTruth = allowTruth,
    allowDare = allowDare,
    allowAdult = allowAdult,
    maxDifficulty = maxDifficulty,
    blockedCategories = blockedCategories.map { it.name },
)

fun StatsDto.toDomain() = PlayerStats(
    xp = xp,
    level = level,
    gamesPlayed = gamesPlayed,
    truthsAnswered = truthsAnswered,
    daresCompleted = daresCompleted,
    daresFailed = daresFailed,
    votesCast = votesCast,
    friendsCount = friendsCount,
    maxDifficultyCompleted = maxDifficultyCompleted,
    unlockedBadges = unlockedBadges.mapNotNull { runCatching { BadgeId.valueOf(it) }.getOrNull() }.toSet(),
)

fun PlayerStats.toDto() = StatsDto(
    xp = xp,
    level = level,
    gamesPlayed = gamesPlayed,
    truthsAnswered = truthsAnswered,
    daresCompleted = daresCompleted,
    daresFailed = daresFailed,
    votesCast = votesCast,
    friendsCount = friendsCount,
    maxDifficultyCompleted = maxDifficultyCompleted,
    unlockedBadges = unlockedBadges.map { it.name },
)

// ---- Room / Player ----

fun PlayerDto.toDomain() = Player(
    uid = uid,
    displayName = displayName,
    photoUrl = photoUrl,
    level = level,
    micEnabled = micEnabled,
    cameraEnabled = cameraEnabled,
    speaking = speaking,
    pingMs = pingMs,
    connection = connection.toEnum(ConnectionQuality.GOOD),
    isHost = isHost,
    joinedAtEpochMs = joinedAtEpochMs,
)

fun Player.toDto() = PlayerDto(
    uid = uid,
    displayName = displayName,
    photoUrl = photoUrl,
    level = level,
    micEnabled = micEnabled,
    cameraEnabled = cameraEnabled,
    speaking = speaking,
    pingMs = pingMs,
    connection = connection.name,
    isHost = isHost,
    joinedAtEpochMs = joinedAtEpochMs,
)

fun RoomDto.toDomain() = Room(
    id = id,
    hostUid = hostUid,
    visibility = visibility.toEnum(RoomVisibility.PUBLIC),
    status = status.toEnum(RoomStatus.WAITING),
    players = players.map { it.toDomain() },
    languageCode = languageCode,
    countryCode = countryCode,
    adultOnly = adultOnly,
    inviteCode = inviteCode,
    maxPlayers = maxPlayers,
    createdAtEpochMs = createdAtEpochMs,
)

// ---- Game ----

fun DifficultyVoteDto.toDomain() = DifficultyVote(voterUid = voterUid, value = value)
fun DifficultyVote.toDto() = DifficultyVoteDto(voterUid = voterUid, value = value)

fun DifficultyResultDto.toDomain() = DifficultyResult(average = average, target = target, voteCount = voteCount)
fun DifficultyResult.toDto() = DifficultyResultDto(average = average, target = target, voteCount = voteCount)

fun ChallengeDto.toDomain() = Challenge(
    id = id,
    type = type.toEnum(ChallengeType.TRUTH),
    text = text,
    category = category.toEnum(ChallengeCategory.FUNNY),
    difficulty = difficulty,
    languageCode = languageCode,
    isAdult = isAdult,
    signature = signature,
    generatedAtEpochMs = generatedAtEpochMs,
)

fun Challenge.toDto() = ChallengeDto(
    id = id,
    type = type.name,
    text = text,
    category = category.name,
    difficulty = difficulty,
    languageCode = languageCode,
    isAdult = isAdult,
    signature = signature,
    generatedAtEpochMs = generatedAtEpochMs,
)

fun TurnDto.toDomain() = Turn(
    index = index,
    activeUid = activeUid,
    type = type?.let { runCatching { ChallengeType.valueOf(it) }.getOrNull() },
    votes = votes.map { it.toDomain() },
    difficulty = difficulty?.toDomain(),
    challenge = challenge?.toDomain(),
    outcome = outcome.toEnum(RoundOutcome.PENDING),
)

fun Turn.toDto() = TurnDto(
    index = index,
    activeUid = activeUid,
    type = type?.name,
    votes = votes.map { it.toDto() },
    difficulty = difficulty?.toDto(),
    challenge = challenge?.toDto(),
    outcome = outcome.name,
)

fun GameSessionDto.toDomain() = GameSession(
    roomId = roomId,
    phase = phase.toEnum(GamePhase.LOBBY),
    turnOrder = turnOrder,
    activeIndex = activeIndex,
    round = round,
    maxRounds = maxRounds,
    currentTurn = currentTurn?.toDomain(),
    history = history.map { it.toDomain() },
    startedAtEpochMs = startedAtEpochMs,
)

fun GameSession.toDto() = GameSessionDto(
    roomId = roomId,
    phase = phase.name,
    turnOrder = turnOrder,
    activeIndex = activeIndex,
    round = round,
    maxRounds = maxRounds,
    currentTurn = currentTurn?.toDto(),
    history = history.map { it.toDto() },
    startedAtEpochMs = startedAtEpochMs,
)

// ---- Social ----

fun FriendDto.toDomain() = Friend(
    uid = uid,
    displayName = displayName,
    photoUrl = photoUrl,
    level = level,
    status = status.toEnum(FriendStatus.ACCEPTED),
    presence = presence.toEnum(Presence.OFFLINE),
    countryCode = countryCode,
)

fun FriendRequestDto.toDomain() = FriendRequest(
    id = id,
    fromUid = fromUid,
    toUid = toUid,
    fromDisplayName = fromDisplayName,
    createdAtEpochMs = createdAtEpochMs,
)

fun ChatMessageDto.toDomain() = ChatMessage(
    id = id,
    senderUid = senderUid,
    senderName = senderName,
    text = text,
    sentAtEpochMs = sentAtEpochMs,
    moderated = moderated,
)
