package com.actionverite.live.data.repository

import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.FunctionsService
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.repository.MatchmakingRepository
import com.actionverite.live.domain.repository.MatchmakingState
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hybrid matchmaking: the client publishes itself to the `matchQueue` and asks
 * the backend ([FunctionsService.requestMatch]) to form/assign a room. The
 * backend runs the same scoring as the on-device [com.actionverite.live.domain
 * .usecase.matchmaking.MatchmakingScorer] for authority, while the client can
 * also rank the pool locally for instant UI.
 */
@Singleton
class MatchmakingRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FunctionsService,
) : MatchmakingRepository {

    private val queue get() = firestore.collection(FirestorePaths.MATCH_QUEUE)

    override fun enqueue(self: MatchProfile, preferences: MatchPreferences): Flow<MatchmakingState> = flow {
        emit(MatchmakingState.Searching(elapsedMs = 0, poolSize = 0))
        try {
            queue.document(self.uid).set(self.toQueueMap(preferences)).await()
            val roomId = functions.requestMatch(preferences.toMap(self))
            if (roomId != null) {
                emit(MatchmakingState.Found(roomId))
            } else {
                emit(MatchmakingState.Searching(elapsedMs = 0, poolSize = 0))
            }
        } catch (e: Throwable) {
            emit(MatchmakingState.Failed(e.message ?: "Matchmaking failed"))
        }
    }

    override suspend fun candidatePool(preferences: MatchPreferences): DomainResult<List<MatchProfile>> =
        firebaseCall {
            // The full ranking/filtering runs in the domain scorer; here we just
            // pull a bounded candidate snapshot from the queue.
            queue.limit(50).get().await().documents.mapNotNull { it.toMatchProfile() }
        }

    override suspend fun cancel(): DomainResult<Unit> = firebaseCall {
        val uid = auth.currentUser?.uid ?: return@firebaseCall Unit
        queue.document(uid).delete().await()
        Unit
    }

    private fun MatchProfile.toQueueMap(prefs: MatchPreferences): Map<String, Any?> = mapOf(
        "uid" to uid,
        "languageCode" to languageCode,
        "countryCode" to countryCode,
        "age" to age,
        "level" to level,
        "interests" to interests.map { it.name },
        "allowAdult" to allowAdult,
        "pingMs" to pingMs,
        "desiredSize" to prefs.desiredSize,
        "enqueuedAt" to System.currentTimeMillis(),
    )

    private fun MatchPreferences.toMap(self: MatchProfile): Map<String, Any?> = mapOf(
        "desiredSize" to desiredSize,
        "sameLanguageOnly" to sameLanguageOnly,
        "sameCountryOnly" to sameCountryOnly,
        "adultOnly" to adultOnly,
        "languageCode" to self.languageCode,
        "countryCode" to self.countryCode,
    )

    private fun com.google.firebase.firestore.DocumentSnapshot.toMatchProfile(): MatchProfile? {
        val uid = getString("uid") ?: return null
        return MatchProfile(
            uid = uid,
            languageCode = getString("languageCode") ?: "en",
            countryCode = getString("countryCode") ?: "US",
            age = getLong("age")?.toInt(),
            level = getLong("level")?.toInt() ?: 1,
            interests = (get("interests") as? List<*>).orEmpty()
                .mapNotNull { it as? String }
                .mapNotNull { runCatching { Interest.valueOf(it) }.getOrNull() }
                .toSet(),
            allowAdult = getBoolean("allowAdult") ?: false,
            pingMs = getLong("pingMs")?.toInt() ?: 0,
        )
    }
}
