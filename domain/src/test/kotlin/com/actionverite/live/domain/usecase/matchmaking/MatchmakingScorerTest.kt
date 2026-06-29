package com.actionverite.live.domain.usecase.matchmaking

import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.ReputationTier
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
        reputationTier = ReputationTier.EXCELLENT,
    )

    @Test
    fun `an identical excellent-reputation candidate scores a perfect match`() {
        val twin = self.copy(uid = "twin")
        assertThat(scorer.score(self, twin)).isWithin(1e-9).of(1.0)
    }

    @Test
    fun `same country outranks same language`() {
        val sameCountryDiffLang = self.copy(uid = "a", languageCode = "fr")
        val diffCountrySameLang = self.copy(uid = "b", countryCode = "CA")
        // Losing the smaller language weight must hurt less than losing country.
        assertThat(scorer.score(self, sameCountryDiffLang))
            .isGreaterThan(scorer.score(self, diffCountrySameLang))
    }

    @Test
    fun `weights are removed when a dimension fully mismatches`() {
        assertThat(scorer.score(self, self.copy(uid = "c", countryCode = "CA")))
            .isWithin(1e-9).of(1.0 - MatchmakingScorer.W_COUNTRY)
        assertThat(scorer.score(self, self.copy(uid = "l", languageCode = "fr")))
            .isWithin(1e-9).of(1.0 - MatchmakingScorer.W_LANGUAGE)
        assertThat(scorer.score(self, self.copy(uid = "i", interests = setOf(Interest.SPORTS))))
            .isWithin(1e-9).of(1.0 - MatchmakingScorer.W_INTERESTS)
    }

    @Test
    fun `lower reputation tiers reduce the score, with NEW above FEW_RATINGS`() {
        val good = scorer.score(self, self.copy(uid = "g", reputationTier = ReputationTier.GOOD))
        val newAcct = scorer.score(self, self.copy(uid = "n", reputationTier = ReputationTier.NEW))
        val few = scorer.score(self, self.copy(uid = "f", reputationTier = ReputationTier.FEW_RATINGS))

        assertThat(good).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_REPUTATION * (1.0 - 0.8))
        assertThat(newAcct).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_REPUTATION * (1.0 - 0.6))
        assertThat(few).isWithin(1e-9).of(1.0 - MatchmakingScorer.W_REPUTATION * (1.0 - 0.5))
        // New accounts are prioritized over merely few-rated ones, per the spec.
        assertThat(newAcct).isGreaterThan(few)
    }

    @Test
    fun `score stays within the unit range for the worst candidate`() {
        val worst = MatchProfile(
            uid = "worst", languageCode = "zz", countryCode = "ZZ", age = 80, level = 999,
            interests = emptySet(), allowAdult = false, pingMs = 10_000,
            reputationTier = ReputationTier.FEW_RATINGS,
        )
        val s = scorer.score(self, worst)
        assertThat(s).isAtLeast(0.0)
        assertThat(s).isAtMost(1.0)
    }
}
