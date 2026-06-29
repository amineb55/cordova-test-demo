package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult

/** FCM token registration + topic subscriptions. */
interface NotificationRepository {
    suspend fun registerDeviceToken(): DomainResult<Unit>
    suspend fun unregisterDeviceToken(): DomainResult<Unit>
    suspend fun setFriendInvitesEnabled(enabled: Boolean): DomainResult<Unit>
}
