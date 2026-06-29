package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.MatchResult
import com.actionverite.live.domain.repository.MatchmakingRepository
import javax.inject.Inject

/**
 * Pulls the current candidate pool and forms the best compatible group locally.
 * (In production the server may also run this; the client copy gives instant
 * feedback and offline-resilient ranking.)
 */
class FindMatchUseCase @Inject constructor(
    private val matchmakingRepository: MatchmakingRepository,
    private val formMatch: FormMatchUseCase,
) {

    suspend operator fun invoke(
        self: MatchProfile,
        preferences: MatchPreferences = MatchPreferences(),
    ): DomainResult<MatchResult> =
        when (val pool = matchmakingRepository.candidatePool(preferences)) {
            is DomainResult.Failure -> pool
            is DomainResult.Success -> {
                val result = formMatch(self, pool.data, preferences)
                if (result == null) {
                    DomainResult.Failure(DomainError.NotFound("compatible players"))
                } else {
                    DomainResult.Success(result)
                }
            }
        }
}
