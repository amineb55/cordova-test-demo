package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.FunctionsService
import com.actionverite.live.data.remote.dto.ReputationDto
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.PlayerRating
import com.actionverite.live.domain.model.Reputation
import com.actionverite.live.domain.repository.ReputationRepository
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReputationRepositoryImpl @Inject constructor(
    private val firestore: FirebaseFirestore,
    private val functions: FunctionsService,
) : ReputationRepository {

    override fun observeReputation(uid: String): Flow<Reputation> =
        firestore.collection(FirestorePaths.USERS).document(uid).asFlow()
            .map { snapshot ->
                snapshot?.get("reputation")
                    ?.let { snapshot.toObjectField("reputation") }
                    ?: Reputation.NEW_ACCOUNT
            }

    override suspend fun submitRatings(
        roomId: String,
        ratings: List<PlayerRating>,
    ): DomainResult<Unit> = firebaseCall {
        functions.submitRatings(
            roomId = roomId,
            ratings = ratings.map { mapOf("targetUid" to it.targetUid, "score" to it.score) },
        )
    }

    /** Reads the nested `reputation` map field into a [Reputation] domain model. */
    private fun com.google.firebase.firestore.DocumentSnapshot.toObjectField(field: String): Reputation? {
        @Suppress("UNCHECKED_CAST")
        val map = get(field) as? Map<String, Any?> ?: return null
        return ReputationDto(
            averageScore = (map["averageScore"] as? Number)?.toDouble() ?: 0.0,
            ratingsCount = (map["ratingsCount"] as? Number)?.toInt() ?: 0,
            tier = map["tier"] as? String ?: "NEW",
        ).toDomain()
    }
}
