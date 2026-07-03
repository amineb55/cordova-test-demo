package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.dto.FriendDto
import com.actionverite.live.data.remote.dto.FriendRequestDto
import com.actionverite.live.data.remote.dto.UserDto
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Friend
import com.actionverite.live.domain.model.FriendRequest
import com.actionverite.live.domain.model.FriendStatus
import com.actionverite.live.domain.repository.FriendRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FriendRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : FriendRepository {

    private val users get() = firestore.collection(FirestorePaths.USERS)
    private val requests get() = firestore.collection(FirestorePaths.FRIEND_REQUESTS)
    private val uid get() = auth.currentUser?.uid

    override fun observeFriends(): Flow<List<Friend>> {
        val me = uid ?: return flowOf(emptyList())
        return users.document(me).collection(FirestorePaths.FRIENDS).asFlow()
            .map { snap -> snap.documents.mapNotNull { it.toObject(FriendDto::class.java)?.toDomain() } }
    }

    override fun observeIncomingRequests(): Flow<List<FriendRequest>> {
        val me = uid ?: return flowOf(emptyList())
        return requests.whereEqualTo("toUid", me).asFlow()
            .map { snap -> snap.documents.mapNotNull { it.toObject(FriendRequestDto::class.java)?.toDomain() } }
    }

    override suspend fun sendRequest(toUid: String): DomainResult<Unit> = withUser { me ->
        val name = users.document(me).get().await().getString("displayName") ?: ""
        val ref = requests.document()
        ref.set(
            FriendRequestDto(
                id = ref.id,
                fromUid = me,
                toUid = toUid,
                fromDisplayName = name,
                createdAtEpochMs = System.currentTimeMillis(),
            ),
        ).await()
        Unit
    }

    override suspend fun acceptRequest(requestId: String): DomainResult<Unit> = withUser { me ->
        val req = requests.document(requestId).get().await().toObject(FriendRequestDto::class.java)
            ?: throw NoSuchElementException("request")
        val a = users.document(me)
        val b = users.document(req.fromUid)
        firestore.runBatch { batch ->
            batch.set(a.collection(FirestorePaths.FRIENDS).document(req.fromUid), friendStub(req.fromUid))
            batch.set(b.collection(FirestorePaths.FRIENDS).document(me), friendStub(me))
            batch.delete(requests.document(requestId))
        }.await()
        Unit
    }

    override suspend fun declineRequest(requestId: String): DomainResult<Unit> = firebaseCall {
        requests.document(requestId).delete().await()
        Unit
    }

    override suspend fun removeFriend(uid: String): DomainResult<Unit> = withUser { me ->
        firestore.runBatch { batch ->
            batch.delete(users.document(me).collection(FirestorePaths.FRIENDS).document(uid))
            batch.delete(users.document(uid).collection(FirestorePaths.FRIENDS).document(me))
        }.await()
        Unit
    }

    override suspend fun blockUser(uid: String): DomainResult<Unit> = withUser { me ->
        users.document(me).collection(FirestorePaths.FRIENDS).document(uid)
            .set(FriendDto(uid = uid, status = FriendStatus.BLOCKED.name)).await()
        Unit
    }

    override suspend fun searchUsers(query: String): DomainResult<List<Friend>> = firebaseCall {
        // Prefix search on displayName: [query, query + high-code-point).
        users.orderBy("displayName")
            .startAt(query)
            .endAt(query + PREFIX_END)
            .limit(20)
            .get().await()
            .documents.mapNotNull { it.toObject(UserDto::class.java) }
            .map {
                Friend(
                    uid = it.uid,
                    displayName = it.displayName,
                    photoUrl = it.photoUrl,
                    level = it.stats.level,
                    countryCode = it.countryCode,
                )
            }
    }

    private fun friendStub(uid: String) = FriendDto(uid = uid, status = FriendStatus.ACCEPTED.name)

    private suspend inline fun <T> withUser(crossinline block: suspend (String) -> T): DomainResult<T> {
        val me = uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return firebaseCall { block(me) }
    }

    private companion object {
        // Private Use Area code point sorts after any normal character.
        const val PREFIX_END = ""
    }
}
