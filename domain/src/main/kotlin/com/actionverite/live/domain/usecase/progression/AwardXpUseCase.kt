package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.XpAward
import com.actionverite.live.domain.model.XpReason
import javax.inject.Inject

/**
 * Computes the XP awarded for a game event. Completed dares scale with the voted
 * difficulty so harder dares are worth more: a difficulty-1 dare pays the base
 * rate, a difficulty-5 dare pays double.
 */
class AwardXpUseCase @Inject constructor() {

    operator fun invoke(reason: XpReason, difficulty: Int? = null): XpAward {
        val multiplier = when (reason) {
            XpReason.DARE_COMPLETED -> difficultyMultiplier(difficulty)
            else -> 1f
        }
        return XpAward(reason = reason, amount = reason.baseXp, multiplier = multiplier)
    }

    /** 1.0x at difficulty 1 up to 2.0x at difficulty 5 (linear, +0.25 per step). */
    private fun difficultyMultiplier(difficulty: Int?): Float {
        val d = Difficulty.clamp(difficulty ?: Difficulty.MIN)
        return 1f + (d - Difficulty.MIN) * 0.25f
    }
}
