package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.model.Challenge
import com.actionverite.live.domain.model.ChallengeCategory
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.DifficultyVote
import com.actionverite.live.domain.model.GamePhase
import com.actionverite.live.domain.model.GameSession
import com.actionverite.live.domain.model.RoundOutcome
import com.actionverite.live.domain.model.Turn
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class GameStateMachineTest {

    private val machine = GameStateMachine(AggregateDifficultyUseCase())
    private val order = listOf("a", "b", "c")

    private fun lobby() = GameSession(roomId = "room")

    private fun started() = machine.reduce(lobby(), GameEvent.StartGame(order))

    @Test
    fun `start moves to choosing type with the first player active`() {
        val s = started()
        assertThat(s.phase).isEqualTo(GamePhase.CHOOSING_TYPE)
        assertThat(s.turnOrder).isEqualTo(order)
        assertThat(s.activeIndex).isEqualTo(0)
        assertThat(s.round).isEqualTo(1)
        assertThat(s.currentTurn?.activeUid).isEqualTo("a")
    }

    @Test
    fun `start validates size and uniqueness and lobby precondition`() {
        assertThrows(IllegalArgumentException::class.java) {
            machine.reduce(lobby(), GameEvent.StartGame(listOf("solo")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            machine.reduce(lobby(), GameEvent.StartGame(listOf("a", "a")))
        }
        assertThrows(IllegalStateException::class.java) {
            machine.reduce(started(), GameEvent.StartGame(order))
        }
    }

    @Test
    fun `choosing truth skips voting and goes straight to generating`() {
        val s = machine.reduce(started(), GameEvent.ChooseType(ChallengeType.TRUTH))
        assertThat(s.phase).isEqualTo(GamePhase.GENERATING)
        assertThat(s.currentTurn?.type).isEqualTo(ChallengeType.TRUTH)
    }

    @Test
    fun `dare collects votes then aggregates difficulty on close`() {
        var s = machine.reduce(started(), GameEvent.ChooseType(ChallengeType.DARE))
        assertThat(s.phase).isEqualTo(GamePhase.COLLECTING_VOTES)

        s = machine.reduce(s, GameEvent.CastVote(DifficultyVote("b", 4)))
        s = machine.reduce(s, GameEvent.CastVote(DifficultyVote("c", 2)))
        assertThat(s.currentTurn?.votes).hasSize(2)

        s = machine.reduce(s, GameEvent.CloseVoting)
        assertThat(s.phase).isEqualTo(GamePhase.GENERATING)
        assertThat(s.currentTurn?.difficulty?.average).isWithin(1e-9).of(3.0)
        assertThat(s.currentTurn?.difficulty?.target).isEqualTo(3)
    }

    @Test
    fun `active player cannot vote on their own dare`() {
        val s = machine.reduce(started(), GameEvent.ChooseType(ChallengeType.DARE))
        assertThrows(IllegalArgumentException::class.java) {
            machine.reduce(s, GameEvent.CastVote(DifficultyVote("a", 5)))
        }
    }

    @Test
    fun `recasting a vote replaces the previous one`() {
        var s = machine.reduce(started(), GameEvent.ChooseType(ChallengeType.DARE))
        s = machine.reduce(s, GameEvent.CastVote(DifficultyVote("b", 1)))
        s = machine.reduce(s, GameEvent.CastVote(DifficultyVote("b", 5)))
        assertThat(s.currentTurn?.votes).hasSize(1)
        assertThat(s.currentTurn?.votes?.first()?.value).isEqualTo(5)
    }

    @Test
    fun `full turn flow advances to the next player`() {
        var s = machine.reduce(started(), GameEvent.ChooseType(ChallengeType.TRUTH))
        s = machine.reduce(s, GameEvent.ChallengeReady(sampleChallenge()))
        assertThat(s.phase).isEqualTo(GamePhase.PERFORMING)

        s = machine.reduce(s, GameEvent.SubmitOutcome(completed = true))
        assertThat(s.phase).isEqualTo(GamePhase.RATING)
        assertThat(s.currentTurn?.outcome).isEqualTo(RoundOutcome.COMPLETED)

        s = machine.reduce(s, GameEvent.NextTurn(order.toSet()))
        assertThat(s.phase).isEqualTo(GamePhase.CHOOSING_TYPE)
        assertThat(s.activeIndex).isEqualTo(1)
        assertThat(s.currentTurn?.activeUid).isEqualTo("b")
        assertThat(s.history).hasSize(1)
        assertThat(s.round).isEqualTo(1)
    }

    @Test
    fun `round increments when the rotation wraps`() {
        val ratingOnLast = GameSession(
            roomId = "room",
            phase = GamePhase.RATING,
            turnOrder = order,
            activeIndex = 2,
            round = 1,
            currentTurn = Turn(index = 5, activeUid = "c", type = ChallengeType.TRUTH, outcome = RoundOutcome.COMPLETED),
        )
        val s = machine.reduce(ratingOnLast, GameEvent.NextTurn(order.toSet()))
        assertThat(s.activeIndex).isEqualTo(0)
        assertThat(s.round).isEqualTo(2)
        assertThat(s.currentTurn?.index).isEqualTo(6)
    }

    @Test
    fun `game finishes after the max round`() {
        val lastRound = GameSession(
            roomId = "room",
            phase = GamePhase.RATING,
            turnOrder = order,
            activeIndex = 2,
            round = 3,
            maxRounds = 3,
            currentTurn = Turn(index = 8, activeUid = "c", outcome = RoundOutcome.COMPLETED),
        )
        val s = machine.reduce(lastRound, GameEvent.NextTurn(order.toSet()))
        assertThat(s.phase).isEqualTo(GamePhase.FINISHED)
        assertThat(s.currentTurn).isNull()
    }

    @Test
    fun `game finishes when fewer than two players remain`() {
        val rating = GameSession(
            roomId = "room",
            phase = GamePhase.RATING,
            turnOrder = order,
            activeIndex = 0,
            round = 1,
            currentTurn = Turn(index = 0, activeUid = "a", outcome = RoundOutcome.COMPLETED),
        )
        val s = machine.reduce(rating, GameEvent.NextTurn(presentUids = setOf("a")))
        assertThat(s.phase).isEqualTo(GamePhase.FINISHED)
    }

    @Test
    fun `end game forces finished from any phase`() {
        assertThat(machine.reduce(started(), GameEvent.EndGame).phase).isEqualTo(GamePhase.FINISHED)
    }

    @Test
    fun `illegal transitions are rejected`() {
        // Cannot choose type while generating.
        val generating = machine.reduce(started(), GameEvent.ChooseType(ChallengeType.TRUTH))
        assertThrows(IllegalStateException::class.java) {
            machine.reduce(generating, GameEvent.ChooseType(ChallengeType.DARE))
        }
        // Cannot vote outside collecting votes.
        assertThrows(IllegalStateException::class.java) {
            machine.reduce(started(), GameEvent.CastVote(DifficultyVote("b", 3)))
        }
    }

    private fun sampleChallenge() = Challenge(
        id = "c1",
        type = ChallengeType.TRUTH,
        text = "What is your happiest memory?",
        category = ChallengeCategory.DEEP,
    )
}
