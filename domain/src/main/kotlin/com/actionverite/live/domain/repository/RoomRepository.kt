package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.model.RoomVisibility
import kotlinx.coroutines.flow.Flow

interface RoomRepository {
    fun observeRoom(roomId: String): Flow<Room?>

    suspend fun createRoom(
        visibility: RoomVisibility,
        languageCode: String,
        adultOnly: Boolean,
    ): DomainResult<Room>

    suspend fun joinRoom(roomId: String): DomainResult<Room>

    suspend fun joinByInviteCode(code: String): DomainResult<Room>

    suspend fun leaveRoom(roomId: String): DomainResult<Unit>

    suspend fun setMediaState(
        roomId: String,
        micEnabled: Boolean,
        cameraEnabled: Boolean,
    ): DomainResult<Unit>

    suspend fun reportPing(roomId: String, pingMs: Int): DomainResult<Unit>
}
