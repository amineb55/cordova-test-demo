package com.actionverite.live.domain.model

/**
 * Aggregate of all server-driven configuration, fetched from Firebase Remote
 * Config. Centralizing it means every tunable rule/price/threshold flows from one
 * source and can change without shipping a new app version.
 */
data class AppConfig(
    val gold: GoldConfig = GoldConfig.DEFAULT,
    val reputation: ReputationConfig = ReputationConfig.DEFAULT,
) {
    companion object {
        val DEFAULT = AppConfig()
    }
}
