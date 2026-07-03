package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeRequest
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.DifficultyResult
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.ChallengeRepository
import com.actionverite.live.domain.usecase.moderation.FilterChallengeUseCase
import javax.inject.Inject

/**
 * Requests a personalized AI challenge for the target player and guarantees it is
 * safe and non-repetitive before returning it. Combines:
 *  - the user's full personalization context (age/lang/country/interests/limits),
 *  - anti-repeat via recent signatures, and
 *  - a moderation/limits gate ([FilterChallengeUseCase]) with bounded retries.
 */
class RequestChallengeUseCase @Inject constructor(
    private val challengeRepository: ChallengeRepository,
    private val filterChallenge: FilterChallengeUseCase,
) {

    suspend operator fun invoke(
        profile: UserProfile,
        type: ChallengeType,
        difficulty: DifficultyResult?,
        todayEpochDay: Long,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): DomainResult<Challenge> {
        val age = profile.ageOn(todayEpochDay)
        val recent = challengeRepository.recentSignatures(profile.uid)
            .getOrNull().orEmpty().toMutableList()

        var lastError: DomainError = DomainError.Unknown("No challenge generated.")

        repeat(maxAttempts) {
            val request = ChallengeRequest(
                type = type,
                targetUid = profile.uid,
                languageCode = profile.languageCode,
                countryCode = profile.countryCode,
                age = age,
                interests = profile.interests,
                limits = profile.limits,
                difficulty = difficulty.takeIf { type == ChallengeType.DARE },
                recentSignatures = recent.toList(),
            )

            when (val generated = challengeRepository.generate(request)) {
                is DomainResult.Failure -> lastError = generated.error
                is DomainResult.Success -> {
                    val challenge = generated.data
                    when (val gate = filterChallenge(challenge, profile.limits, age)) {
                        is DomainResult.Success -> {
                            challengeRepository.recordServed(profile.uid, challenge)
                            return gate
                        }
                        is DomainResult.Failure -> {
                            lastError = gate.error
                            // Avoid regenerating the same rejected prompt next attempt.
                            if (challenge.signature.isNotBlank()) recent.add(challenge.signature)
                        }
                    }
                }
            }
        }
        return DomainResult.Failure(lastError)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 3
    }
}
