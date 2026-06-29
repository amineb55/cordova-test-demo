package com.actionverite.live.domain.model

enum class FriendStatus { NONE, PENDING_OUTGOING, PENDING_INCOMING, ACCEPTED, BLOCKED }

enum class Presence { ONLINE, IN_GAME, AWAY, OFFLINE }

data class Friend(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val level: Int = 1,
    val status: FriendStatus = FriendStatus.NONE,
    val presence: Presence = Presence.OFFLINE,
    val countryCode: String? = null,
)

data class FriendRequest(
    val id: String,
    val fromUid: String,
    val toUid: String,
    val fromDisplayName: String,
    val createdAtEpochMs: Long = 0L,
)

/** A chat message in a room or a direct conversation. */
data class ChatMessage(
    val id: String,
    val senderUid: String,
    val senderName: String,
    val text: String,
    val sentAtEpochMs: Long = 0L,
    val moderated: Boolean = false,
)
