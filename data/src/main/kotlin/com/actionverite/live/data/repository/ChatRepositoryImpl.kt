package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.dto.ChatMessageDto
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ChatMessage
import com.actionverite.live.domain.repository.ChatRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : ChatRepository {

    private val rooms get() = firestore.collection(FirestorePaths.ROOMS)
    private val users get() = firestore.collection(FirestorePaths.USERS)

    override fun observeRoomChat(roomId: String): Flow<List<ChatMessage>> =
        rooms.document(roomId).collection(FirestorePaths.MESSAGES)
            .orderBy("sentAtEpochMs", Query.Direction.ASCENDING)
            .asFlow()
            .map { snap -> snap.documents.mapNotNull { it.toObject(ChatMessageDto::class.java)?.toDomain() } }

    override fun observeDirectChat(peerUid: String): Flow<List<ChatMessage>> {
        val me = auth.currentUser?.uid ?: return flowOf(emptyList())
        return users.document(me).collection(FirestorePaths.DM).document(peerUid)
            .collection(FirestorePaths.MESSAGES)
            .orderBy("sentAtEpochMs", Query.Direction.ASCENDING)
            .asFlow()
            .map { snap -> snap.documents.mapNotNull { it.toObject(ChatMessageDto::class.java)?.toDomain() } }
    }

    override suspend fun sendToRoom(roomId: String, text: String): DomainResult<Unit> = withMessage(text) { msg ->
        val ref = rooms.document(roomId).collection(FirestorePaths.MESSAGES).document()
        ref.set(msg.copy(id = ref.id)).await()
    }

    override suspend fun sendDirect(peerUid: String, text: String): DomainResult<Unit> = withMessage(text) { msg ->
        val me = msg.senderUid
        // Write to both participants' mailboxes so each can read their own subtree.
        firestore.runBatch { batch ->
            val mine = users.document(me).collection(FirestorePaths.DM).document(peerUid)
                .collection(FirestorePaths.MESSAGES).document()
            val theirs = users.document(peerUid).collection(FirestorePaths.DM).document(me)
                .collection(FirestorePaths.MESSAGES).document(mine.id)
            batch.set(mine, msg.copy(id = mine.id))
            batch.set(theirs, msg.copy(id = mine.id))
        }.await()
    }

    private suspend inline fun withMessage(
        text: String,
        crossinline write: suspend (ChatMessageDto) -> Unit,
    ): DomainResult<Unit> {
        val user = auth.currentUser ?: return DomainResult.Failure(DomainError.Unauthenticated)
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return DomainResult.Failure(DomainError.Validation("Empty message"))
        val msg = ChatMessageDto(
            senderUid = user.uid,
            senderName = user.displayName ?: "",
            text = trimmed.take(MAX_LEN),
            sentAtEpochMs = System.currentTimeMillis(),
        )
        return firebaseCall { write(msg) }
    }

    private companion object {
        const val MAX_LEN = 500
    }
}
