package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.AppConfig
import kotlinx.coroutines.flow.Flow

/**
 * Server-driven configuration (Firebase Remote Config). Lets all rules, rewards,
 * prices and thresholds change without an app update, per the spec.
 */
interface RemoteConfigRepository {
    /** Latest cached config; emits again after a successful [refresh]. */
    fun observeConfig(): Flow<AppConfig>

    /** Current config snapshot (cached, with sane defaults if never fetched). */
    fun current(): AppConfig

    /** Fetches and activates the newest values from the server. */
    suspend fun refresh(): DomainResult<AppConfig>
}
