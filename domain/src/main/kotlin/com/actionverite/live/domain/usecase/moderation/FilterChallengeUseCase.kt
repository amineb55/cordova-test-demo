package com.actionverite.live.domain.usecase.moderation

import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.ContentLimits
import javax.inject.Inject

/**
 * Defense-in-depth gate applied to an AI-generated challenge *before* it is shown
 * to the target player. Enforces per-user [ContentLimits], the player's age, and
 * a final on-device moderation pass.
 *
 * Returns the challenge on success, or a typed [DomainError] explaining why it
 * was rejected (so the caller can transparently request a replacement).
 */
class FilterChallengeUseCase @Inject constructor(
    private val moderator: ContentModerator,
) {

    operator fun invoke(
        challenge: Challenge,
        limits: ContentLimits,
        age: Int?,
    ): DomainResult<Challenge> {
        val isAdult = age == null || age >= 18

        if (challenge.isAdult && (!limits.allowAdult || !isAdult)) {
            return DomainResult.Failure(DomainError.Underage)
        }
        if (challenge.type == ChallengeType.TRUTH && !limits.allowTruth) {
            return DomainResult.Failure(DomainError.Moderation("Truth is disabled in your limits."))
        }
        if (challenge.type == ChallengeType.DARE && !limits.allowDare) {
            return DomainResult.Failure(DomainError.Moderation("Dare is disabled in your limits."))
        }
        if (challenge.category in limits.blockedCategories) {
            return DomainResult.Failure(DomainError.Moderation("Category is blocked in your limits."))
        }
        if (challenge.difficulty > limits.maxDifficulty) {
            return DomainResult.Failure(DomainError.Moderation("Above your maximum difficulty."))
        }

        val moderation = moderator.screen(challenge.text, allowAdult = limits.allowAdult && isAdult)
        if (!moderation.isAllowed) {
            return DomainResult.Failure(DomainError.Moderation(moderation.reason ?: "Blocked by moderation."))
        }

        return DomainResult.Success(challenge)
    }
}
