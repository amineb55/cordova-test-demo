package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ConnectionQuality
import kotlinx.coroutines.flow.Flow

/** A remote or local media track handle, opaque to the domain. */
data class MediaTrackRef(
    val uid: String,
    val isLocal: Boolean,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
)

/** Per-peer realtime stats sampled from the WebRTC connection. */
data class RtcStats(
    val uid: String,
    val rttMs: Int,
    val quality: ConnectionQuality,
    val packetLoss: Float,
)

/**
 * WebRTC session boundary. The implementation in :data manages PeerConnections,
 * the signaling channel (over Firestore/Functions) and TURN credentials. The
 * domain only deals with abstract track refs and stats.
 */
interface RtcRepository {
    suspend fun connect(roomId: String, selfUid: String): DomainResult<Unit>
    suspend fun disconnect()

    fun observeTracks(): Flow<List<MediaTrackRef>>
    fun observeStats(): Flow<List<RtcStats>>

    suspend fun setMicrophoneEnabled(enabled: Boolean)
    suspend fun setCameraEnabled(enabled: Boolean)
    suspend fun switchCamera()
}
