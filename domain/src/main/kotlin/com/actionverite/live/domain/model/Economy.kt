package com.actionverite.live.domain.model

/**
 * Server-configurable Gold economy parameters. Loaded from Firebase Remote Config
 * so prices/rewards can change without an app update (spec: "Toutes les règles,
 * récompenses, prix et paramètres doivent être configurables depuis le serveur").
 * The defaults encode the values from the design doc.
 */
data class GoldConfig(
    val joinCost: Int = 10,            // min Gold required AND charged to play a game
    val rewardSuccess: Int = 1,        // completing a dare / answering a truth
    val penaltyRefuse: Int = 3,        // refusing a question or action (floored at 0)
    val rewardedVideoGold: Int = 4,    // watching a rewarded ad
    val referenceGoldPerUsd: Int = 40, // base display rate: 40 Gold = 1 USD
    val packs: List<GoldPack> = DEFAULT_PACKS,
) {
    companion object {
        val DEFAULT_PACKS = listOf(
            GoldPack(id = "pack_40", gold = 40, priceUsdCents = 100),    // 40 Gold = 1 USD
            GoldPack(id = "pack_500", gold = 500, priceUsdCents = 1000), // 500 Gold = 10 USD (bonus)
        )
        val DEFAULT = GoldConfig()
    }
}

/** A purchasable Gold pack. Pricing is configured server-side. */
data class GoldPack(
    val id: String,
    val gold: Int,
    val priceUsdCents: Int,
) {
    /** Gold per US dollar — higher is better value for the buyer. */
    val goldPerUsd: Double
        get() = if (priceUsdCents <= 0) 0.0 else gold / (priceUsdCents / 100.0)

    val priceUsd: Double get() = priceUsdCents / 100.0
}

/** Why a Gold balance changed; persisted on each transaction for auditing. */
enum class GoldReason {
    GAME_JOIN, CHALLENGE_SUCCESS, REFUSAL, REWARDED_VIDEO, PURCHASE, ADMIN_GRANT,
}

/**
 * The outcome of applying a Gold rule: the resulting (never-negative) [newBalance]
 * and the [delta] actually applied (which may be smaller in magnitude than the
 * nominal amount when a penalty is clamped at zero).
 */
data class GoldChange(
    val newBalance: Long,
    val delta: Long,
    val reason: GoldReason,
)
