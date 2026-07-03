package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.authStateFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.dto.UserDto
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.data.remote.dto.toDto
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.PlayerStats
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.UserRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
) : UserRepository {

    private val users get() = firestore.collection(FirestorePaths.USERS)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeCurrentProfile(): Flow<UserProfile?> = auth.authStateFlow()
        .flatMapLatest { uid ->
            if (uid == null) flowOf(null)
            else users.document(uid).asFlow().map { it?.toObject(UserDto::class.java)?.toDomain() }
        }

    override suspend fun getProfile(uid: String): DomainResult<UserProfile> = firebaseCall {
        val dto = users.document(uid).get().await().toObject(UserDto::class.java)
            ?: throw NoSuchElementException("profile")
        dto.toDomain()
    }

    override suspend fun createOrUpdateProfile(profile: UserProfile): DomainResult<Unit> = firebaseCall {
        users.document(profile.uid).set(profile.toDto()).await()
        Unit
    }

    override suspend fun updateInterests(interests: Set<Interest>): DomainResult<Unit> = withUid { uid ->
        users.document(uid).update("interests", interests.map { it.name }).await()
        Unit
    }

    override suspend fun updateLimits(limits: ContentLimits): DomainResult<Unit> = withUid { uid ->
        users.document(uid).update("limits", limits.toDto()).await()
        Unit
    }

    override suspend fun updateStats(uid: String, stats: PlayerStats): DomainResult<Unit> = firebaseCall {
        users.document(uid).update("stats", stats.toDto()).await()
        Unit
    }

    override suspend fun isDisplayNameAvailable(name: String): DomainResult<Boolean> = firebaseCall {
        users.whereEqualTo("displayName", name).limit(1).get().await().isEmpty
    }

    private suspend inline fun withUid(crossinline block: suspend (String) -> Unit): DomainResult<Unit> {
        val uid = auth.currentUser?.uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return firebaseCall { block(uid) }
    }
}
