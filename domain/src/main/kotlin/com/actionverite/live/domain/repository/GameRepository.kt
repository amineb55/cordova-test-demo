package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.GameSession
import kotlinx.coroutines.flow.Flow

/**
 * Persists and streams the authoritative [GameSession]. State transitions are
 * computed locally by the GameStateMachine and committed here (or, in
 * production, validated/committed by a Cloud Function for anti-cheat).
 */
interface GameRepository {
    fun observeSession(roomId: String): Flow<GameSession?>

    suspend fun startGame(roomId: String, turnOrder: List<String>): DomainResult<GameSession>

    suspend fun chooseType(roomId: String, type: ChallengeType): DomainResult<Unit>

    suspend fun castDifficultyVote(roomId: String, value: Int): DomainResult<Unit>

    suspend fun submitOutcome(roomId: String, completed: Boolean): DomainResult<Unit>

    suspend fun commitSession(session: GameSession): DomainResult<Unit>

    suspend fun endGame(roomId: String): DomainResult<Unit>
}
