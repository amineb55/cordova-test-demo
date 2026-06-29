package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.DifficultyVote
import com.actionverite.live.domain.model.GamePhase
import com.actionverite.live.domain.model.GameSession
import com.actionverite.live.domain.model.RoundOutcome
import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.model.Turn
import javax.inject.Inject

/** Events that drive the game forward. The reducer is the single source of truth. */
sealed interface GameEvent {
    data class StartGame(val turnOrder: List<String>) : GameEvent
    data class ChooseType(val type: ChallengeType) : GameEvent
    data class CastVote(val vote: DifficultyVote) : GameEvent
    data object CloseVoting : GameEvent
    data class ChallengeReady(val challenge: Challenge) : GameEvent
    data class SubmitOutcome(val completed: Boolean) : GameEvent
    data class NextTurn(val presentUids: Set<String> = emptySet()) : GameEvent
    data object EndGame : GameEvent
}

/**
 * Pure, deterministic reducer for a [GameSession]. It encodes the full turn
 * lifecycle from the spec and is the only place transitions are allowed, which
 * keeps the rules in one auditable spot and makes them exhaustively testable.
 *
 * Invalid transitions throw [IllegalStateException]; invalid arguments throw
 * [IllegalArgumentException]. Callers should treat these as protocol/programmer
 * errors, not user-facing failures.
 */
class GameStateMachine @Inject constructor(
    private val aggregateDifficulty: AggregateDifficultyUseCase,
) {

    fun reduce(session: GameSession, event: GameEvent): GameSession = when (event) {
        is GameEvent.StartGame -> startGame(session, event.turnOrder)
        is GameEvent.ChooseType -> chooseType(session, event.type)
        is GameEvent.CastVote -> castVote(session, event.vote)
        GameEvent.CloseVoting -> closeVoting(session)
        is GameEvent.ChallengeReady -> challengeReady(session, event.challenge)
        is GameEvent.SubmitOutcome -> submitOutcome(session, event.completed)
        is GameEvent.NextTurn -> nextTurn(session, event.presentUids)
        GameEvent.EndGame -> session.copy(phase = GamePhase.FINISHED)
    }

    private fun startGame(session: GameSession, order: List<String>): GameSession {
        check(session.phase == GamePhase.LOBBY) { "Can only start from LOBBY (was ${session.phase})" }
        require(order.size in Room.MIN_PLAYERS..Room.MAX_PLAYERS) {
            "turnOrder size must be in ${Room.MIN_PLAYERS}..${Room.MAX_PLAYERS}, was ${order.size}"
        }
        require(order.toSet().size == order.size) { "turnOrder must not contain duplicates" }
        return session.copy(
            phase = GamePhase.CHOOSING_TYPE,
            turnOrder = order,
            activeIndex = 0,
            round = 1,
            currentTurn = Turn(index = 0, activeUid = order.first()),
            history = emptyList(),
        )
    }

    private fun chooseType(session: GameSession, type: ChallengeType): GameSession {
        check(session.phase == GamePhase.CHOOSING_TYPE) { "Cannot choose type in ${session.phase}" }
        val turn = requireTurn(session).copy(type = type)
        val nextPhase = if (type == ChallengeType.DARE) GamePhase.COLLECTING_VOTES else GamePhase.GENERATING
        return session.copy(phase = nextPhase, currentTurn = turn)
    }

    private fun castVote(session: GameSession, vote: DifficultyVote): GameSession {
        check(session.phase == GamePhase.COLLECTING_VOTES) { "Cannot vote in ${session.phase}" }
        val turn = requireTurn(session)
        require(vote.voterUid != turn.activeUid) { "The active player cannot vote on their own dare" }
        // Replace any previous vote by the same voter.
        val votes = turn.votes.filterNot { it.voterUid == vote.voterUid } + vote
        return session.copy(currentTurn = turn.copy(votes = votes))
    }

    private fun closeVoting(session: GameSession): GameSession {
        check(session.phase == GamePhase.COLLECTING_VOTES) { "Cannot close voting in ${session.phase}" }
        val turn = requireTurn(session)
        val result = aggregateDifficulty(turn.votes, activePlayerUid = turn.activeUid)
        return session.copy(
            phase = GamePhase.GENERATING,
            currentTurn = turn.copy(difficulty = result),
        )
    }

    private fun challengeReady(session: GameSession, challenge: Challenge): GameSession {
        check(session.phase == GamePhase.GENERATING) { "No challenge expected in ${session.phase}" }
        return session.copy(
            phase = GamePhase.PERFORMING,
            currentTurn = requireTurn(session).copy(challenge = challenge),
        )
    }

    private fun submitOutcome(session: GameSession, completed: Boolean): GameSession {
        check(session.phase == GamePhase.PERFORMING) { "Cannot submit outcome in ${session.phase}" }
        val outcome = if (completed) RoundOutcome.COMPLETED else RoundOutcome.FAILED
        return session.copy(
            phase = GamePhase.RATING,
            currentTurn = requireTurn(session).copy(outcome = outcome),
        )
    }

    private fun nextTurn(session: GameSession, presentUids: Set<String>): GameSession {
        check(session.phase == GamePhase.RATING) { "Cannot advance turn in ${session.phase}" }
        val finished = requireTurn(session)
        val history = session.history + finished

        // Default everyone present when caller does not supply presence info.
        val present = presentUids.ifEmpty { session.turnOrder.toSet() }
        val presentInOrder = session.turnOrder.count { it in present }
        if (presentInOrder < Room.MIN_PLAYERS) {
            return session.copy(phase = GamePhase.FINISHED, currentTurn = null, history = history)
        }

        val advance = TurnRotation.advance(session.turnOrder, session.activeIndex, present)
        val newRound = session.round + if (advance.wrapped) 1 else 0
        if (newRound > session.maxRounds) {
            return session.copy(phase = GamePhase.FINISHED, currentTurn = null, history = history)
        }

        return session.copy(
            phase = GamePhase.CHOOSING_TYPE,
            activeIndex = advance.index,
            round = newRound,
            currentTurn = Turn(index = finished.index + 1, activeUid = session.turnOrder[advance.index]),
            history = history,
        )
    }

    private fun requireTurn(session: GameSession): Turn =
        session.currentTurn ?: error("No active turn in phase ${session.phase}")
}
