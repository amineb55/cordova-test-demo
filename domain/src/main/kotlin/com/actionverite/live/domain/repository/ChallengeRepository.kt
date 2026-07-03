package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeRequest

/**
 * AI challenge generation boundary. The implementation calls a secured Cloud
 * Function (so the model API key never ships in the app). The function applies
 * server-side moderation before returning, but the domain re-checks via
 * [ModerationRepository] as defense in depth.
 */
interface ChallengeRepository {
    suspend fun generate(request: ChallengeRequest): DomainResult<Challenge>

    /** Recent challenge signatures for a user, used to avoid repeats. */
    suspend fun recentSignatures(uid: String, limit: Int = 50): DomainResult<List<String>>

    suspend fun recordServed(uid: String, challenge: Challenge): DomainResult<Unit>
}
