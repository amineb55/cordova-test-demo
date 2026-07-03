package com.actionverite.live.domain.usecase.economy

import com.actionverite.live.domain.model.GoldConfig
import com.actionverite.live.domain.model.GoldReason
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoldEconomyUseCaseTest {

    private val economy = GoldEconomyUseCase()
    private val config = GoldConfig.DEFAULT // join 10, success +1, refuse -3, rewardedVideo +4

    @Test
    fun `canJoin requires at least the join cost`() {
        assertThat(economy.canJoin(10, config)).isTrue()
        assertThat(economy.canJoin(9, config)).isFalse()
    }

    @Test
    fun `charging for a game deducts the entry fee`() {
        val change = economy.chargeForGame(25, config)
        assertThat(change.newBalance).isEqualTo(15)
        assertThat(change.delta).isEqualTo(-10)
        assertThat(change.reason).isEqualTo(GoldReason.GAME_JOIN)
    }

    @Test
    fun `success reward credits one gold`() {
        val change = economy.rewardSuccess(0, config)
        assertThat(change.newBalance).isEqualTo(1)
        assertThat(change.delta).isEqualTo(1)
    }

    @Test
    fun `refusal penalty is clamped so the balance never goes negative`() {
        // Full penalty when affordable.
        assertThat(economy.penalizeRefusal(10, config).newBalance).isEqualTo(7)
        // "Si le joueur possède moins de 3 Gold, son solde devient 0."
        val clamped = economy.penalizeRefusal(2, config)
        assertThat(clamped.newBalance).isEqualTo(0)
        assertThat(clamped.delta).isEqualTo(-2) // only what was available was removed
    }

    @Test
    fun `rewarded video grants the configured gold`() {
        assertThat(economy.grantRewardedVideo(5, config).newBalance).isEqualTo(9)
    }

    @Test
    fun `purchasing a pack credits its gold`() {
        val pack = config.packs.first { it.id == "pack_500" }
        val change = economy.applyPurchase(0, pack)
        assertThat(change.newBalance).isEqualTo(500)
        assertThat(change.reason).isEqualTo(GoldReason.PURCHASE)
    }

    @Test
    fun `rules honour server config overrides`() {
        val custom = config.copy(joinCost = 20, penaltyRefuse = 5)
        assertThat(economy.canJoin(15, custom)).isFalse()
        assertThat(economy.penalizeRefusal(4, custom).newBalance).isEqualTo(0)
    }
}
