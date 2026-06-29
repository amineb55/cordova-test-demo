package com.actionverite.live.data.repository

import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.FunctionsService
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.data.remote.dto.toDto
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeRequest
import com.actionverite.live.domain.repository.ChallengeRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChallengeRepositoryImpl @Inject constructor(
    private val functions: FunctionsService,
    private val firestore: FirebaseFirestore,
) : ChallengeRepository {

    private fun servedCol(uid: String) =
        firestore.collection(FirestorePaths.USERS).document(uid)
            .collection(FirestorePaths.SERVED_CHALLENGES)

    override suspend fun generate(request: ChallengeRequest): DomainResult<Challenge> = firebaseCall {
        functions.generateChallenge(request).toDomain()
    }

    override suspend fun recentSignatures(uid: String, limit: Int): DomainResult<List<String>> = firebaseCall {
        servedCol(uid)
            .orderBy("generatedAtEpochMs", Query.Direction.DESCENDING)
            .limit(limit.toLong())
            .get().await()
            .documents.mapNotNull { it.getString("signature") }
    }

    override suspend fun recordServed(uid: String, challenge: Challenge): DomainResult<Unit> = firebaseCall {
        val sig = challenge.signature.ifBlank { challenge.id }
        servedCol(uid).document(sig).set(challenge.toDto()).await()
        Unit
    }
}
