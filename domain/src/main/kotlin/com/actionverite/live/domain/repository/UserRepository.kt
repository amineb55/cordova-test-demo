package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.PlayerStats
import com.actionverite.live.domain.model.UserProfile
import kotlinx.coroutines.flow.Flow

interface UserRepository {
    /** Realtime stream of the signed-in user's profile. */
    fun observeCurrentProfile(): Flow<UserProfile?>

    suspend fun getProfile(uid: String): DomainResult<UserProfile>

    suspend fun createOrUpdateProfile(profile: UserProfile): DomainResult<Unit>

    suspend fun updateInterests(interests: Set<Interest>): DomainResult<Unit>

    suspend fun updateLimits(limits: ContentLimits): DomainResult<Unit>

    suspend fun updateStats(uid: String, stats: PlayerStats): DomainResult<Unit>

    suspend fun isDisplayNameAvailable(name: String): DomainResult<Boolean>
}
