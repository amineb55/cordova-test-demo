package com.actionverite.live.domain.usecase.economy

import com.actionverite.live.domain.model.GoldChange
import com.actionverite.live.domain.model.GoldConfig
import com.actionverite.live.domain.model.GoldPack
import com.actionverite.live.domain.model.GoldReason
import javax.inject.Inject

/**
 * Pure, config-driven Gold rules (spec "ÉCONOMIE GOLD"). Balances never go
 * negative: penalties are clamped at zero so a player with 2 Gold who refuses a
 * dare (−3) ends at 0, and the reported [GoldChange.delta] reflects what was
 * actually deducted.
 *
 * This computes the *intended* change; the authoritative mutation is performed
 * server-side (see EconomyRepository / Cloud Functions) for anti-cheat.
 */
class GoldEconomyUseCase @Inject constructor() {

    /** Whether the player has enough Gold to join/start a game. */
    fun canJoin(balance: Long, config: GoldConfig): Boolean = balance >= config.joinCost

    /** Charges the game entry fee (clamped at zero). */
    fun chargeForGame(balance: Long, config: GoldConfig): GoldChange =
        debit(balance, config.joinCost.toLong(), GoldReason.GAME_JOIN)

    /** Reward for completing a dare or answering a truth. */
    fun rewardSuccess(balance: Long, config: GoldConfig): GoldChange =
        credit(balance, config.rewardSuccess.toLong(), GoldReason.CHALLENGE_SUCCESS)

    /** Penalty for refusing a question or action (clamped at zero). */
    fun penalizeRefusal(balance: Long, config: GoldConfig): GoldChange =
        debit(balance, config.penaltyRefuse.toLong(), GoldReason.REFUSAL)

    /** Reward for watching a rewarded video ad. */
    fun grantRewardedVideo(balance: Long, config: GoldConfig): GoldChange =
        credit(balance, config.rewardedVideoGold.toLong(), GoldReason.REWARDED_VIDEO)

    /** Adds the Gold from a purchased pack. */
    fun applyPurchase(balance: Long, pack: GoldPack): GoldChange =
        credit(balance, pack.gold.toLong(), GoldReason.PURCHASE)

    private fun credit(balance: Long, amount: Long, reason: GoldReason): GoldChange {
        val safeBalance = balance.coerceAtLeast(0)
        val delta = amount.coerceAtLeast(0)
        return GoldChange(newBalance = safeBalance + delta, delta = delta, reason = reason)
    }

    private fun debit(balance: Long, amount: Long, reason: GoldReason): GoldChange {
        val safeBalance = balance.coerceAtLeast(0)
        val newBalance = (safeBalance - amount.coerceAtLeast(0)).coerceAtLeast(0)
        return GoldChange(newBalance = newBalance, delta = newBalance - safeBalance, reason = reason)
    }
}
