package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.BadgeId
import com.actionverite.live.domain.model.PlayerStats
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EvaluateBadgesUseCaseTest {

    private val evaluate = EvaluateBadgesUseCase()

    @Test
    fun `no badges for an empty profile`() {
        assertThat(evaluate(PlayerStats.EMPTY)).isEmpty()
    }

    @Test
    fun `first game unlocks the first-game badge`() {
        val newly = evaluate(PlayerStats.EMPTY.copy(gamesPlayed = 1))
        assertThat(newly).contains(BadgeId.FIRST_GAME)
    }

    @Test
    fun `milestone badges unlock at their thresholds`() {
        val stats = PlayerStats(
            level = 25,
            truthsAnswered = 25,
            daresCompleted = 25,
            maxDifficultyCompleted = 5,
            friendsCount = 10,
            votesCast = 100,
            gamesPlayed = 50,
        )
        val badges = evaluate(stats)
        assertThat(badges).containsAtLeast(
            BadgeId.LEVEL_10,
            BadgeId.LEVEL_25,
            BadgeId.TRUTH_SEEKER,
            BadgeId.DAREDEVIL,
            BadgeId.FEARLESS,
            BadgeId.SOCIAL_BUTTERFLY,
            BadgeId.PERFECT_VOTER,
            BadgeId.GLOBETROTTER,
        )
        assertThat(badges).doesNotContain(BadgeId.LEVEL_50)
    }

    @Test
    fun `already-unlocked badges are not returned again`() {
        val stats = PlayerStats(gamesPlayed = 1, unlockedBadges = setOf(BadgeId.FIRST_GAME))
        assertThat(evaluate(stats)).isEmpty()
    }
}
