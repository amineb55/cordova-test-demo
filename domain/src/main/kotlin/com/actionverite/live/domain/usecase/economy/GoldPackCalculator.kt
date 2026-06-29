package com.actionverite.live.domain.usecase.economy

import com.actionverite.live.domain.model.GoldConfig
import com.actionverite.live.domain.model.GoldPack
import javax.inject.Inject
import kotlin.math.roundToLong

/**
 * Helpers for the store UI: convert between Gold and USD using the configured
 * reference rate, and rank packs by value. All pure and config-driven.
 */
class GoldPackCalculator @Inject constructor() {

    /** Reference USD-cents value of a Gold amount (40 Gold = 1 USD by default). */
    fun goldToUsdCents(gold: Long, config: GoldConfig): Long {
        val rate = config.referenceGoldPerUsd.coerceAtLeast(1)
        return ((gold.toDouble() / rate) * 100.0).roundToLong()
    }

    /** Reference Gold value of a USD-cents amount. */
    fun usdCentsToGold(usdCents: Long, config: GoldConfig): Long {
        val rate = config.referenceGoldPerUsd.coerceAtLeast(1)
        return ((usdCents / 100.0) * rate).roundToLong()
    }

    /** Bonus Gold a pack gives over the flat reference rate (0 if none). */
    fun bonusGold(pack: GoldPack, config: GoldConfig): Long {
        val baseline = usdCentsToGold(pack.priceUsdCents.toLong(), config)
        return (pack.gold - baseline).coerceAtLeast(0)
    }

    /** The best-value pack (most Gold per USD), or null if there are none. */
    fun bestValuePack(config: GoldConfig): GoldPack? =
        config.packs.maxByOrNull { it.goldPerUsd }
}
