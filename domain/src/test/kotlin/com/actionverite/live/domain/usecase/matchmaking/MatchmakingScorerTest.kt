package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.MatchProfile
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MatchmakingScorerTest {

    private val scorer = MatchmakingScorer()

    private val self = MatchProfile(
        uid = "self",
        languageCode = "en",
        countryCode = "US",
        age = 25,
        level = 10,
        interests = setOf(Interest.MUSIC, Interest.GAMING),
        allowAdult = true,
        pingMs = 0,
    )

    @Test
    fun `an identical candidate scores a perfect match`() {
        val twin = self.copy(uid = "twin")
        assertThat(scorer.score(self, twin)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `different language removes the language weight`() {
        val other = self.copy(uid = "fr", languageCode = "fr")
        assertThat(scorer.score(self, other)).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_LANGUAGE)
    }

    @Test
    fun `no shared interests removes the interests weight`() {
        val other = self.copy(uid = "x", interests = setOf(Interest.SPORTS))
        assertThat(scorer.score(self, other)).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_INTERESTS)
    }

    @Test
    fun `high ping erodes the ping weight`() {
        val laggy = self.copy(uid = "lag", pingMs = 300) // == maxPingMs
        assertThat(scorer.score(self, laggy)).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_PING)
    }

    @Test
    fun `max level gap removes the level weight`() {
        val farLevel = self.copy(uid = "lvl", level = 20) // gap 10 == maxLevelGap
        assertThat(scorer.score(self, farLevel)).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_LEVEL)
    }

    @Test
    fun `unknown age yields a neutral half age weight`() {
        val noAge = self.copy(uid = "na", age = null)
        // Age term contributes 0.5 instead of 1.0 → lose half the age weight.
        assertThat(scorer.score(self, noAge)).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_AGE * 0.5)
    }

    @Test
    fun `score is always within unit range`() {
        val worst = MatchProfile(
            uid = "worst",
            languageCode = "zz",
            countryCode = "ZZ",
            age = 80,
            level = 999,
            interests = emptySet(),
            allowAdult = false,
            pingMs = 10_000,
        )
        val s = scorer.score(self, worst)
        assertThat(s).isAtLeast(0.0)
        assertThat(s).isAtMost(1.0)
    }
}
