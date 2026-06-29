package com.actionverite.live.domain.model

/** Real-time connection health, surfaced as the "ping" indicator on each tile. */
enum class ConnectionQuality { EXCELLENT, GOOD, FAIR, POOR, DISCONNECTED }

/**
 * A participant inside a live room. This is the realtime projection of a
 * [UserProfile] plus media/connection state shown on the video tile
 * (pseudo, photo, niveau, micro, caméra, ping — per the "Caméra" spec section).
 */
data class Player(
    val uid: String,
    val displayName: String,
    val photoUrl: String? = null,
    val level: Int = 1,
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val speaking: Boolean = false,
    val pingMs: Int = 0,
    val connection: ConnectionQuality = ConnectionQuality.GOOD,
    val isHost: Boolean = false,
    val joinedAtEpochMs: Long = 0L,
) {
    val isPresent: Boolean get() = connection != ConnectionQuality.DISCONNECTED

    companion object {
        /** Maps a measured round-trip time to a coarse quality bucket. */
        fun qualityForPing(pingMs: Int): ConnectionQuality = when {
            pingMs < 0 -> ConnectionQuality.DISCONNECTED
            pingMs <= 80 -> ConnectionQuality.EXCELLENT
            pingMs <= 150 -> ConnectionQuality.GOOD
            pingMs <= 250 -> ConnectionQuality.FAIR
            else -> ConnectionQuality.POOR
        }
    }
}
