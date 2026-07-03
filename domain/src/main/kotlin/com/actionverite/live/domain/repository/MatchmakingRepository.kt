package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import kotlinx.coroutines.flow.Flow

/** Emitted while a player waits in the matchmaking queue. */
sealed interface MatchmakingState {
    data object Idle : MatchmakingState
    data class Searching(val elapsedMs: Long, val poolSize: Int) : MatchmakingState
    data class Found(val roomId: String) : MatchmakingState
    data class Failed(val reason: String) : MatchmakingState
}

interface MatchmakingRepository {
    /** Joins the queue and streams progress until a room is found or cancelled. */
    fun enqueue(self: MatchProfile, preferences: MatchPreferences): Flow<MatchmakingState>

    /** Snapshot of currently queued candidates (used by the local scorer). */
    suspend fun candidatePool(preferences: MatchPreferences): DomainResult<List<MatchProfile>>

    suspend fun cancel(): DomainResult<Unit>
}
