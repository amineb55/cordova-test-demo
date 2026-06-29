package com.actionverite.live.data.webrtc

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.remote.FirestorePaths
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/** A signaling message exchanged between two peers via Firestore. */
data class SignalMessage(
    val type: String = "",      // OFFER | ANSWER | CANDIDATE
    val from: String = "",
    val to: String = "",
    val sdp: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Int? = null,
    val candidate: String? = null,
    val createdAt: Long = 0L,
)

/**
 * Firestore-backed WebRTC signaling channel. SDP offers/answers and ICE
 * candidates for a room are written to `rooms/{roomId}/signals` and streamed back
 * to each peer. This keeps signaling serverless (no dedicated socket server)
 * while the actual A/V flows peer-to-peer over the negotiated connections.
 */
@Singleton
class SignalingClient @Inject constructor(
    private val firestore: FirebaseFirestore,
) {
    private fun signals(roomId: String) =
        firestore.collection(FirestorePaths.ROOMS).document(roomId).collection(FirestorePaths.SIGNALS)

    /** Messages addressed to [selfUid] in [roomId], newest first. */
    fun incoming(roomId: String, selfUid: String): Flow<List<SignalMessage>> =
        signals(roomId).whereEqualTo("to", selfUid).asFlow().map { snap ->
            snap.documents.mapNotNull { it.toObject(SignalMessage::class.java) }
        }

    suspend fun send(roomId: String, message: SignalMessage) {
        signals(roomId).add(message.copy(createdAt = System.currentTimeMillis())).await()
    }

    suspend fun clear(roomId: String, selfUid: String) {
        val mine = signals(roomId).whereEqualTo("from", selfUid).get().await()
        mine.documents.forEach { it.reference.delete().await() }
    }

    companion object {
        const val TYPE_OFFER = "OFFER"
        const val TYPE_ANSWER = "ANSWER"
        const val TYPE_CANDIDATE = "CANDIDATE"
    }
}
