package com.actionverite.live.data.repository

import com.actionverite.live.data.common.firebaseCall
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.AppConfig
import com.actionverite.live.domain.model.GoldConfig
import com.actionverite.live.domain.model.GoldPack
import com.actionverite.live.domain.model.ReputationConfig
import com.actionverite.live.domain.repository.RemoteConfigRepository
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads all server-driven configuration from Firebase Remote Config and maps it
 * onto the domain [AppConfig]. Scalars come from typed keys; the Gold packs are a
 * JSON array string so the catalogue can be reshaped server-side. Unset keys fall
 * back to the in-code defaults, so the app always has a sane config offline.
 */
@Singleton
class RemoteConfigRepositoryImpl @Inject constructor(
    private val remoteConfig: FirebaseRemoteConfig,
) : RemoteConfigRepository {

    private val state = MutableStateFlow(parse())

    init {
        // Seed defaults so reads are sane before the first server fetch.
        remoteConfig.setDefaultsAsync(defaults())
    }

    override fun observeConfig(): StateFlow<AppConfig> = state.asStateFlow()

    override fun current(): AppConfig = parse()

    override suspend fun refresh(): DomainResult<AppConfig> = firebaseCall {
        remoteConfig.fetchAndActivate().await()
        parse().also { state.value = it }
    }

    private fun parse(): AppConfig {
        val defaults = GoldConfig.DEFAULT
        val gold = GoldConfig(
            joinCost = remoteConfig.getLong(KEY_JOIN_COST).toIntOr(defaults.joinCost),
            rewardSuccess = remoteConfig.getLong(KEY_REWARD_SUCCESS).toIntOr(defaults.rewardSuccess),
            penaltyRefuse = remoteConfig.getLong(KEY_PENALTY_REFUSE).toIntOr(defaults.penaltyRefuse),
            rewardedVideoGold = remoteConfig.getLong(KEY_REWARDED_VIDEO).toIntOr(defaults.rewardedVideoGold),
            referenceGoldPerUsd = remoteConfig.getLong(KEY_REFERENCE_PER_USD).toIntOr(defaults.referenceGoldPerUsd),
            packs = parsePacks(remoteConfig.getString(KEY_PACKS)).ifEmpty { defaults.packs },
        )
        val repDefaults = ReputationConfig.DEFAULT
        val reputation = ReputationConfig(
            minRatingsForTier = remoteConfig.getLong(KEY_REP_MIN_RATINGS).toIntOr(repDefaults.minRatingsForTier),
            excellentThreshold = remoteConfig.getDouble(KEY_REP_EXCELLENT).orElse(repDefaults.excellentThreshold),
            goodThreshold = remoteConfig.getDouble(KEY_REP_GOOD).orElse(repDefaults.goodThreshold),
        )
        return AppConfig(gold = gold, reputation = reputation)
    }

    private fun parsePacks(json: String): List<GoldPack> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                GoldPack(
                    id = o.getString("id"),
                    gold = o.getInt("gold"),
                    priceUsdCents = o.getInt("priceUsdCents"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun defaults(): Map<String, Any> {
        val g = GoldConfig.DEFAULT
        val packsJson = JSONArray().apply {
            g.packs.forEach { pack ->
                put(
                    org.json.JSONObject()
                        .put("id", pack.id)
                        .put("gold", pack.gold)
                        .put("priceUsdCents", pack.priceUsdCents),
                )
            }
        }.toString()
        return mapOf(
            KEY_JOIN_COST to g.joinCost,
            KEY_REWARD_SUCCESS to g.rewardSuccess,
            KEY_PENALTY_REFUSE to g.penaltyRefuse,
            KEY_REWARDED_VIDEO to g.rewardedVideoGold,
            KEY_REFERENCE_PER_USD to g.referenceGoldPerUsd,
            KEY_PACKS to packsJson,
            KEY_REP_MIN_RATINGS to ReputationConfig.DEFAULT.minRatingsForTier,
            KEY_REP_EXCELLENT to ReputationConfig.DEFAULT.excellentThreshold,
            KEY_REP_GOOD to ReputationConfig.DEFAULT.goodThreshold,
        )
    }

    private fun Long.toIntOr(fallback: Int): Int = if (this == 0L) fallback else toInt()
    private fun Double.orElse(fallback: Double): Double = if (this == 0.0) fallback else this

    private companion object {
        const val KEY_JOIN_COST = "gold_join_cost"
        const val KEY_REWARD_SUCCESS = "gold_reward_success"
        const val KEY_PENALTY_REFUSE = "gold_penalty_refuse"
        const val KEY_REWARDED_VIDEO = "gold_rewarded_video"
        const val KEY_REFERENCE_PER_USD = "gold_reference_per_usd"
        const val KEY_PACKS = "gold_packs"
        const val KEY_REP_MIN_RATINGS = "rep_min_ratings"
        const val KEY_REP_EXCELLENT = "rep_excellent_threshold"
        const val KEY_REP_GOOD = "rep_good_threshold"
    }
}
