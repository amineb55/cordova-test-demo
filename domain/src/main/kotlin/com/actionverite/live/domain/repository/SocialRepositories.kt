package com.actionverite.live.domain.repository

import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ChatMessage
import com.actionverite.live.domain.model.Friend
import com.actionverite.live.domain.model.FriendRequest
import com.actionverite.live.domain.model.LeaderboardEntry
import com.actionverite.live.domain.model.LeaderboardScope
import kotlinx.coroutines.flow.Flow

interface FriendRepository {
    fun observeFriends(): Flow<List<Friend>>
    fun observeIncomingRequests(): Flow<List<FriendRequest>>

    suspend fun sendRequest(toUid: String): DomainResult<Unit>
    suspend fun acceptRequest(requestId: String): DomainResult<Unit>
    suspend fun declineRequest(requestId: String): DomainResult<Unit>
    suspend fun removeFriend(uid: String): DomainResult<Unit>
    suspend fun blockUser(uid: String): DomainResult<Unit>
    suspend fun searchUsers(query: String): DomainResult<List<Friend>>
}

interface ChatRepository {
    fun observeRoomChat(roomId: String): Flow<List<ChatMessage>>
    fun observeDirectChat(peerUid: String): Flow<List<ChatMessage>>

    suspend fun sendToRoom(roomId: String, text: String): DomainResult<Unit>
    suspend fun sendDirect(peerUid: String, text: String): DomainResult<Unit>
}

interface LeaderboardRepository {
    fun observe(scope: LeaderboardScope, limit: Int = 100): Flow<List<LeaderboardEntry>>
    suspend fun myRank(scope: LeaderboardScope): DomainResult<LeaderboardEntry>
}
