package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.model.DifficultyResult
import com.actionverite.live.domain.model.DifficultyVote
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AggregateDifficultyUseCaseTest {

    private val aggregate = AggregateDifficultyUseCase()

    @Test
    fun `no votes yields the neutral result`() {
        assertThat(aggregate(emptyList())).isEqualTo(DifficultyResult.NEUTRAL)
    }

    @Test
    fun `computes mean and rounded target`() {
        val votes = listOf(
            DifficultyVote("a", 3),
            DifficultyVote("b", 4),
            DifficultyVote("c", 5),
        )
        val result = aggregate(votes)
        assertThat(result.average).isWithin(1e-9).of(4.0)
        assertThat(result.target).isEqualTo(4)
        assertThat(result.voteCount).isEqualTo(3)
    }

    @Test
    fun `rounds halves up`() {
        // mean 2.5 -> 3, mean 1.5 -> 2
        assertThat(aggregate(listOf(DifficultyVote("a", 2), DifficultyVote("b", 3))).target).isEqualTo(3)
        assertThat(aggregate(listOf(DifficultyVote("a", 1), DifficultyVote("b", 2))).target).isEqualTo(2)
    }

    @Test
    fun `keeps only the last vote per voter`() {
        val votes = listOf(
            DifficultyVote("a", 1),
            DifficultyVote("a", 5), // a changed their mind
            DifficultyVote("b", 5),
        )
        val result = aggregate(votes)
        assertThat(result.voteCount).isEqualTo(2)
        assertThat(result.average).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `ignores the active player's own vote`() {
        val votes = listOf(
            DifficultyVote("active", 1),
            DifficultyVote("other", 5),
        )
        val result = aggregate(votes, activePlayerUid = "active")
        assertThat(result.voteCount).isEqualTo(1)
        assertThat(result.average).isWithin(1e-9).of(5.0)
        assertThat(result.target).isEqualTo(5)
    }

    @Test
    fun `only the active player voting falls back to neutral`() {
        val result = aggregate(listOf(DifficultyVote("active", 2)), activePlayerUid = "active")
        assertThat(result).isEqualTo(DifficultyResult.NEUTRAL)
    }
}
