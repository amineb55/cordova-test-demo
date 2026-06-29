package com.actionverite.live.domain.usecase.economy

import com.actionverite.live.domain.model.GoldConfig
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GoldPackCalculatorTest {

    private val calc = GoldPackCalculator()
    private val config = GoldConfig.DEFAULT // referenceGoldPerUsd = 40

    @Test
    fun `gold converts to usd cents at the reference rate`() {
        assertThat(calc.goldToUsdCents(40, config)).isEqualTo(100)   // 40 Gold = 1 USD
        assertThat(calc.goldToUsdCents(500, config)).isEqualTo(1250) // reference value of 500 Gold
    }

    @Test
    fun `usd cents convert to gold at the reference rate`() {
        assertThat(calc.usdCentsToGold(100, config)).isEqualTo(40)
        assertThat(calc.usdCentsToGold(1000, config)).isEqualTo(400)
    }

    @Test
    fun `bulk pack awards bonus gold over the flat rate`() {
        val bulk = config.packs.first { it.id == "pack_500" }   // 500 Gold for 10 USD
        val starter = config.packs.first { it.id == "pack_40" } // 40 Gold for 1 USD
        assertThat(calc.bonusGold(bulk, config)).isEqualTo(100) // 500 - (10 USD * 40)
        assertThat(calc.bonusGold(starter, config)).isEqualTo(0)
    }

    @Test
    fun `best value pack is the one with the most gold per usd`() {
        val best = calc.bestValuePack(config)
        assertThat(best?.id).isEqualTo("pack_500") // 50 Gold/USD vs 40 Gold/USD
    }
}
