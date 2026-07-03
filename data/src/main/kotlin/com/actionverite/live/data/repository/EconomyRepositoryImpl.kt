package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.FunctionsService
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.repository.EconomyRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wallet backed by the user document's `gold` field for reads, and by
 * server-authoritative Cloud Functions for every mutation (anti-cheat: clients
 * never write their own balance).
 */
@Singleton
class EconomyRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val functions: FunctionsService,
) : EconomyRepository {

    override fun observeBalance(): Flow<Long> {
        val uid = auth.currentUser?.uid ?: return flowOf(0L)
        return firestore.collection(FirestorePaths.USERS).document(uid).asFlow()
            .map { it?.getLong("gold") ?: 0L }
    }

    override suspend fun chargeForGame(roomId: String): DomainResult<Long> =
        firebaseCall { functions.chargeGameFee(roomId) }

    override suspend fun rewardChallengeSuccess(roomId: String): DomainResult<Long> =
        firebaseCall { functions.rewardChallenge(roomId) }

    override suspend fun penalizeRefusal(roomId: String): DomainResult<Long> =
        firebaseCall { functions.penalizeRefusal(roomId) }

    override suspend fun grantRewardedVideo(adSsvToken: String): DomainResult<Long> =
        firebaseCall { functions.grantRewardedVideo(adSsvToken) }

    override suspend fun purchasePack(packId: String, purchaseToken: String): DomainResult<Long> =
        firebaseCall { functions.purchasePack(packId, purchaseToken) }
}
