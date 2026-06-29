package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.BadgeId
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.PlayerStats
import com.actionverite.live.domain.model.RoundOutcome
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ApplyTurnRewardsUseCaseTest {

    private val apply = ApplyTurnRewardsUseCase(
        awardXp = AwardXpUseCase(),
        levelSystem = LevelSystem(),
        evaluateBadges = EvaluateBadgesUseCase(),
    )

    @Test
    fun `completed truth awards xp and increments counter`() {
        val rewards = apply(PlayerStats.EMPTY, ChallengeType.TRUTH, RoundOutcome.COMPLETED)
        assertThat(rewards.stats.truthsAnswered).isEqualTo(1)
        assertThat(rewards.award?.total).isEqualTo(20)
        assertThat(rewards.stats.xp).isEqualTo(20)
    }

    @Test
    fun `completed hard dare scales xp, tracks max difficulty and earns FEARLESS`() {
        val rewards = apply(PlayerStats.EMPTY, ChallengeType.DARE, RoundOutcome.COMPLETED, difficultyTarget = 5)
        assertThat(rewards.stats.daresCompleted).isEqualTo(1)
        assertThat(rewards.stats.maxDifficultyCompleted).isEqualTo(5)
        assertThat(rewards.award?.total).isEqualTo(60)
        assertThat(rewards.newBadges).contains(BadgeId.FEARLESS)
        assertThat(rewards.stats.unlockedBadges).contains(BadgeId.FEARLESS)
    }

    @Test
    fun `failed dare awards consolation xp`() {
        val rewards = apply(PlayerStats.EMPTY, ChallengeType.DARE, RoundOutcome.FAILED)
        assertThat(rewards.stats.daresFailed).isEqualTo(1)
        assertThat(rewards.award?.total).isEqualTo(5)
    }

    @Test
    fun `non-terminal outcomes grant nothing`() {
        val rewards = apply(PlayerStats.EMPTY, ChallengeType.TRUTH, RoundOutcome.SKIPPED)
        assertThat(rewards.award).isNull()
        assertThat(rewards.stats).isEqualTo(PlayerStats.EMPTY)
    }
}
