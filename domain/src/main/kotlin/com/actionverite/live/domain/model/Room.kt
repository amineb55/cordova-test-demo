package com.actionverite.live.domain.model

/** Public rooms feed matchmaking; private rooms are invite/code only. */
enum class RoomVisibility { PUBLIC, PRIVATE }

enum class RoomStatus { WAITING, IN_PROGRESS, FINISHED, CLOSED }

/**
 * A game room hosting 2–6 players (spec: "2 à 6 joueurs"). The capacity bounds
 * are enforced here so every layer agrees on the same limits.
 */
data class Room(
    val id: String,
    val hostUid: String,
    val visibility: RoomVisibility,
    val status: RoomStatus = RoomStatus.WAITING,
    val players: List<Player> = emptyList(),
    val languageCode: String = "en",
    val countryCode: String? = null,
    val adultOnly: Boolean = false,
    val inviteCode: String? = null,
    val maxPlayers: Int = MAX_PLAYERS,
    val createdAtEpochMs: Long = 0L,
) {
    val playerCount: Int get() = players.size
    val presentCount: Int get() = players.count { it.isPresent }
    val isFull: Boolean get() = playerCount >= maxPlayers
    val canStart: Boolean get() = presentCount in MIN_PLAYERS..MAX_PLAYERS

    fun hasRoom(): Boolean = playerCount < maxPlayers

    companion object {
        const val MIN_PLAYERS = 2
        const val MAX_PLAYERS = 6
    }
}
