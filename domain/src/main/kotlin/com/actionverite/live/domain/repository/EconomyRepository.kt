package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import kotlinx.coroutines.flow.Flow

/**
 * Server-authoritative Gold wallet. The client computes intended changes with
 * [com.actionverite.live.domain.usecase.economy.GoldEconomyUseCase], but every
 * real mutation goes through a Cloud Function (anti-cheat), which validates and
 * returns the new balance.
 */
interface EconomyRepository {
    /** Realtime Gold balance for the signed-in user. */
    fun observeBalance(): Flow<Long>

    /** Charges the entry fee to join/start [roomId]; fails if balance is insufficient. */
    suspend fun chargeForGame(roomId: String): DomainResult<Long>

    /** Credits the success reward for a completed turn in [roomId]. */
    suspend fun rewardChallengeSuccess(roomId: String): DomainResult<Long>

    /** Applies the refusal penalty (clamped at zero) for [roomId]. */
    suspend fun penalizeRefusal(roomId: String): DomainResult<Long>

    /** Credits a rewarded-video grant after server-side ad-SSV verification. */
    suspend fun grantRewardedVideo(adSsvToken: String): DomainResult<Long>

    /** Validates a store purchase ([purchaseToken]) and credits the pack's Gold. */
    suspend fun purchasePack(packId: String, purchaseToken: String): DomainResult<Long>
}
