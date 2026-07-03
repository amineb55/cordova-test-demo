package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.PlayerRating
import com.actionverite.live.domain.model.Reputation
import kotlinx.coroutines.flow.Flow

/**
 * Post-game peer ratings and aggregated reputation. Ratings are submitted at the
 * end of a game; the server aggregates them into each target's [Reputation]
 * (running average + tier) which then feeds matchmaking.
 */
interface ReputationRepository {
    fun observeReputation(uid: String): Flow<Reputation>

    /** Submit this player's end-of-game ratings of the others in [roomId]. */
    suspend fun submitRatings(roomId: String, ratings: List<PlayerRating>): DomainResult<Unit>
}
