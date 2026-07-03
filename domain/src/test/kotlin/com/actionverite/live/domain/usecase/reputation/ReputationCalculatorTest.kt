package com.actionverite.live.domain.usecase.reputation

import com.actionverite.live.domain.model.Reputation
import com.actionverite.live.domain.model.ReputationTier
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class ReputationCalculatorTest {

    private val calc = ReputationCalculator() // defaults: minRatings 5, excellent 4.5, good 3.5

    @Test
    fun `unrated accounts are NEW`() {
        assertThat(calc.tierFor(0.0, 0)).isEqualTo(ReputationTier.NEW)
    }

    @Test
    fun `rated but below confidence threshold is FEW_RATINGS`() {
        assertThat(calc.tierFor(4.9, 3)).isEqualTo(ReputationTier.FEW_RATINGS)
    }

    @Test
    fun `established accounts are graded by average`() {
        assertThat(calc.tierFor(4.8, 10)).isEqualTo(ReputationTier.EXCELLENT)
        assertThat(calc.tierFor(4.0, 10)).isEqualTo(ReputationTier.GOOD)
        assertThat(calc.tierFor(2.0, 10)).isEqualTo(ReputationTier.GOOD)
    }

    @Test
    fun `applying the first rating moves a new account to FEW_RATINGS`() {
        val updated = calc.applyRating(Reputation.NEW_ACCOUNT, 5)
        assertThat(updated.ratingsCount).isEqualTo(1)
        assertThat(updated.averageScore).isWithin(1e-9).of(5.0)
        assertThat(updated.tier).isEqualTo(ReputationTier.FEW_RATINGS)
    }

    @Test
    fun `applying a rating folds into the running average and re-grades the tier`() {
        val current = Reputation(averageScore = 4.0, ratingsCount = 4, tier = ReputationTier.FEW_RATINGS)
        val updated = calc.applyRating(current, 5) // (4*4 + 5)/5 = 4.2, now 5 ratings
        assertThat(updated.ratingsCount).isEqualTo(5)
        assertThat(updated.averageScore).isWithin(1e-9).of(4.2)
        assertThat(updated.tier).isEqualTo(ReputationTier.GOOD)
    }

    @Test
    fun `invalid ratings are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { calc.applyRating(Reputation.NEW_ACCOUNT, 0) }
        assertThrows(IllegalArgumentException::class.java) { calc.applyRating(Reputation.NEW_ACCOUNT, 6) }
    }
}
