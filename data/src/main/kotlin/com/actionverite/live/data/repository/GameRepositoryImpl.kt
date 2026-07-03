package com.actionverite.live.data.repository

import com.actionverite.live.data.common.asFlow
import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.data.remote.FirestorePaths
import com.actionverite.live.data.remote.dto.GameSessionDto
import com.actionverite.live.data.remote.dto.toDomain
import com.actionverite.live.data.remote.dto.toDto
import com.actionverite.live.domain.common.DomainError
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.DifficultyVote
import com.actionverite.live.domain.model.GamePhase
import com.actionverite.live.domain.model.GameSession
import com.actionverite.live.domain.repository.GameRepository
import com.actionverite.live.domain.usecase.game.GameEvent
import com.actionverite.live.domain.usecase.game.GameStateMachine
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads/writes the authoritative [GameSession] document and applies every
 * transition through the shared [GameStateMachine] inside a Firestore
 * transaction (read-modify-write) to prevent lost updates from concurrent
 * players. The identical state-machine rules are mirrored in the Cloud Functions
 * backend for server-side anti-cheat validation (see docs/GAME_DESIGN.md).
 */
@Singleton
class GameRepositoryImpl @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val stateMachine: GameStateMachine,
) : GameRepository {

    private fun stateRef(roomId: String): DocumentReference =
        firestore.collection(FirestorePaths.ROOMS).document(roomId)
            .collection(FirestorePaths.GAME).document(FirestorePaths.GAME_STATE_DOC)

    override fun observeSession(roomId: String): Flow<GameSession?> =
        stateRef(roomId).asFlow().map { it?.toObject(GameSessionDto::class.java)?.toDomain() }

    override suspend fun startGame(roomId: String, turnOrder: List<String>): DomainResult<GameSession> =
        firebaseCall {
            val initial = GameSession(roomId = roomId)
            val started = stateMachine.reduce(initial, GameEvent.StartGame(turnOrder))
            stateRef(roomId).set(started.toDto()).await()
            started
        }

    override suspend fun chooseType(roomId: String, type: ChallengeType): DomainResult<Unit> =
        transition(roomId, GameEvent.ChooseType(type))

    override suspend fun castDifficultyVote(roomId: String, value: Int): DomainResult<Unit> {
        val uid = auth.currentUser?.uid ?: return DomainResult.Failure(DomainError.Unauthenticated)
        return transition(roomId, GameEvent.CastVote(DifficultyVote(uid, value)))
    }

    override suspend fun submitOutcome(roomId: String, completed: Boolean): DomainResult<Unit> =
        transition(roomId, GameEvent.SubmitOutcome(completed))

    override suspend fun commitSession(session: GameSession): DomainResult<Unit> = firebaseCall {
        stateRef(session.roomId).set(session.toDto()).await()
        Unit
    }

    override suspend fun endGame(roomId: String): DomainResult<Unit> =
        transition(roomId, GameEvent.EndGame)

    /** Atomic read-modify-write of the session through the state machine. */
    private suspend fun transition(roomId: String, event: GameEvent): DomainResult<Unit> = firebaseCall {
        val ref = stateRef(roomId)
        firestore.runTransaction { tx ->
            val current = tx.get(ref).toObject(GameSessionDto::class.java)?.toDomain()
                ?: GameSession(roomId = roomId, phase = GamePhase.LOBBY)
            val next = stateMachine.reduce(current, event)
            tx.set(ref, next.toDto())
            null
        }.await()
        Unit
    }
}
