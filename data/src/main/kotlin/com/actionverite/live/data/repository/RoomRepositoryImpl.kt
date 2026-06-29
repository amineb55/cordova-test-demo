package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.common.toDomainError
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.dto.PlayerDto
import com.actionverite.live.data.remote.dto.RoomDto
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.model.RoomVisibility
import com.actionverite.live.domain.repository.RoomRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class RoomRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : RoomRepository {

    private val rooms get() = firestore.collection(FirestorePaths.ROOMS)

    override fun observeRoom(roomId: String): Flow<Room?> =
        rooms.document(roomId).asFlow().map { it?.toObject(RoomDto::class.java)?.toDomain() }

    override suspend fun createRoom(
        visibility: RoomVisibility,
        languageCode: String,
        adultOnly: Boolean,
    ): DomainResult<Room> = withUser { uid ->
        val ref = rooms.document()
        val host = PlayerDto(uid = uid, isHost = true, joinedAtEpochMs = System.currentTimeMillis())
        val dto = RoomDto(
            id = ref.id,
            hostUid = uid,
            visibility = visibility.name,
            players = listOf(host),
            languageCode = languageCode,
            adultOnly = adultOnly,
            inviteCode = if (visibility == RoomVisibility.PRIVATE) generateInviteCode() else null,
            createdAtEpochMs = System.currentTimeMillis(),
        )
        ref.set(dto).await()
        dto.toDomain()
    }

    override suspend fun joinRoom(roomId: String): DomainResult<Room> {
        val uid = auth.currentUser?.uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        val ref = rooms.document(roomId)
        return try {
            firestore.runTransaction { tx ->
                val dto = tx.get(ref).toObject(RoomDto::class.java)
                    ?: throw RoomNotFoundException
                if (dto.players.size >= dto.maxPlayers && dto.players.none { it.uid == uid }) {
                    throw RoomFullException(dto.maxPlayers)
                }
                if (dto.players.none { it.uid == uid }) {
                    val player = PlayerDto(uid = uid, joinedAtEpochMs = System.currentTimeMillis())
                    tx.update(ref, "players", dto.players + player)
                }
                null
            }.await()
            val room = ref.get().await().toObject(RoomDto::class.java)
                ?: return DomainResult.Failure(DomainError.NotFound("room"))
            DomainResult.Success(room.toDomain())
        } catch (e: RoomFullException) {
            DomainResult.Failure(DomainError.RoomFull(e.capacity))
        } catch (e: RoomNotFoundException) {
            DomainResult.Failure(DomainError.NotFound("room"))
        } catch (e: Throwable) {
            DomainResult.Failure(e.toDomainError())
        }
    }

    override suspend fun joinByInviteCode(code: String): DomainResult<Room> {
        val snapshot = firebaseCall {
            rooms.whereEqualTo("inviteCode", code).limit(1).get().await()
        }
        return when (snapshot) {
            is DomainResult.Failure -> snapshot
            is DomainResult.Success -> {
                val roomId = snapshot.data.documents.firstOrNull()?.id
                    ?: return DomainResult.Failure(DomainError.NotFound("invite"))
                joinRoom(roomId)
            }
        }
    }

    override suspend fun leaveRoom(roomId: String): DomainResult<Unit> = withUser { uid ->
        val ref = rooms.document(roomId)
        firestore.runTransaction { tx ->
            val dto = tx.get(ref).toObject(RoomDto::class.java) ?: return@runTransaction null
            tx.update(ref, "players", dto.players.filterNot { it.uid == uid })
            null
        }.await()
        Unit
    }

    override suspend fun setMediaState(
        roomId: String,
        micEnabled: Boolean,
        cameraEnabled: Boolean,
    ): DomainResult<Unit> = updatePlayer(roomId) { it.copy(micEnabled = micEnabled, cameraEnabled = cameraEnabled) }

    override suspend fun reportPing(roomId: String, pingMs: Int): DomainResult<Unit> =
        updatePlayer(roomId) { it.copy(pingMs = pingMs) }

    private suspend fun updatePlayer(
        roomId: String,
        transform: (PlayerDto) -> PlayerDto,
    ): DomainResult<Unit> = withUser { uid ->
        val ref = rooms.document(roomId)
        firestore.runTransaction { tx ->
            val dto = tx.get(ref).toObject(RoomDto::class.java) ?: return@runTransaction null
            val updated = dto.players.map { if (it.uid == uid) transform(it) else it }
            tx.update(ref, "players", updated)
            null
        }.await()
        Unit
    }

    private fun generateInviteCode(): String =
        (1..6).map { INVITE_ALPHABET[Random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")

    private suspend inline fun <T> withUser(crossinline block: suspend (String) -> T): DomainResult<T> {
        val uid = auth.currentUser?.uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return firebaseCall { block(uid) }
    }

    private class RoomFullException(val capacity: Int) : RuntimeException("Room full")
    private object RoomNotFoundException : RuntimeException("Room not found")

    companion object {
        // No ambiguous characters (0/O, 1/I) for codes that may be read aloud.
        private const val INVITE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
