package com.actionverite.live.domain.usecase.progression

import com.actionverite.live.domain.model.XpReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AwardXpUseCaseTest {

    private val award = AwardXpUseCase()

    @Test
    fun `flat reasons award their base xp`() {
        assertThat(award(XpReason.TRUTH_ANSWERED).total).isEqualTo(20)
        assertThat(award(XpReason.VOTE_CAST).total).isEqualTo(2)
        assertThat(award(XpReason.GAME_FINISHED).total).isEqualTo(15)
    }

    @Test
    fun `completed dares scale with difficulty`() {
        assertThat(award(XpReason.DARE_COMPLETED, difficulty = 1).total).isEqualTo(30)
        assertThat(award(XpReason.DARE_COMPLETED, difficulty = 3).total).isEqualTo(45)
        assertThat(award(XpReason.DARE_COMPLETED, difficulty = 5).total).isEqualTo(60)
    }

    @Test
    fun `missing or out-of-range difficulty is clamped`() {
        assertThat(award(XpReason.DARE_COMPLETED, difficulty = null).total).isEqualTo(30)
        assertThat(award(XpReason.DARE_COMPLETED, difficulty = 99).total).isEqualTo(60)
    }
}
