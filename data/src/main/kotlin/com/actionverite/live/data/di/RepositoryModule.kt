package com.actionverite.live.data.di

import com.actionverite.live.data.common.AndroidDispatcherProvider
import com.actionverite.live.data.repository.AuthRepositoryImpl
import com.actionverite.live.data.repository.ChallengeRepositoryImpl
import com.actionverite.live.data.repository.ChatRepositoryImpl
import com.actionverite.live.data.repository.EconomyRepositoryImpl
import com.actionverite.live.data.repository.FriendRepositoryImpl
import com.actionverite.live.data.repository.GameRepositoryImpl
import com.actionverite.live.data.repository.LeaderboardRepositoryImpl
import com.actionverite.live.data.repository.MatchmakingRepositoryImpl
import com.actionverite.live.data.repository.ModerationRepositoryImpl
import com.actionverite.live.data.repository.NotificationRepositoryImpl
import com.actionverite.live.data.repository.RemoteConfigRepositoryImpl
import com.actionverite.live.data.repository.ReputationRepositoryImpl
import com.actionverite.live.data.repository.RoomRepositoryImpl
import com.actionverite.live.data.repository.UserRepositoryImpl
import com.actionverite.live.data.webrtc.WebRtcRepositoryImpl
import com.actionverite.live.domain.common.DispatcherProvider
import com.actionverite.live.domain.repository.AuthRepository
import com.actionverite.live.domain.repository.ChallengeRepository
import com.actionverite.live.domain.repository.ChatRepository
import com.actionverite.live.domain.repository.FriendRepository
import com.actionverite.live.domain.repository.GameRepository
import com.actionverite.live.domain.repository.LeaderboardRepository
import com.actionverite.live.domain.repository.MatchmakingRepository
import com.actionverite.live.domain.repository.EconomyRepository
import com.actionverite.live.domain.repository.ModerationRepository
import com.actionverite.live.domain.repository.NotificationRepository
import com.actionverite.live.domain.repository.RemoteConfigRepository
import com.actionverite.live.domain.repository.ReputationRepository
import com.actionverite.live.domain.repository.RoomRepository
import com.actionverite.live.domain.repository.RtcRepository
import com.actionverite.live.domain.repository.UserRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds every domain repository interface to its data-layer implementation.
 * `@Binds` is used (not `@Provides`) so Hilt wires the constructor-injected impls
 * with zero boilerplate.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindDispatcherProvider(impl: AndroidDispatcherProvider): DispatcherProvider

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindUserRepository(impl: UserRepositoryImpl): UserRepository

    @Binds
    @Singleton
    abstract fun bindRoomRepository(impl: RoomRepositoryImpl): RoomRepository

    @Binds
    @Singleton
    abstract fun bindGameRepository(impl: GameRepositoryImpl): GameRepository

    @Binds
    @Singleton
    abstract fun bindChallengeRepository(impl: ChallengeRepositoryImpl): ChallengeRepository

    @Binds
    @Singleton
    abstract fun bindMatchmakingRepository(impl: MatchmakingRepositoryImpl): MatchmakingRepository

    @Binds
    @Singleton
    abstract fun bindFriendRepository(impl: FriendRepositoryImpl): FriendRepository

    @Binds
    @Singleton
    abstract fun bindChatRepository(impl: ChatRepositoryImpl): ChatRepository

    @Binds
    @Singleton
    abstract fun bindLeaderboardRepository(impl: LeaderboardRepositoryImpl): LeaderboardRepository

    @Binds
    @Singleton
    abstract fun bindModerationRepository(impl: ModerationRepositoryImpl): ModerationRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRepository(impl: NotificationRepositoryImpl): NotificationRepository

    @Binds
    @Singleton
    abstract fun bindRtcRepository(impl: WebRtcRepositoryImpl): RtcRepository

    @Binds
    @Singleton
    abstract fun bindEconomyRepository(impl: EconomyRepositoryImpl): EconomyRepository

    @Binds
    @Singleton
    abstract fun bindReputationRepository(impl: ReputationRepositoryImpl): ReputationRepository

    @Binds
    @Singleton
    abstract fun bindRemoteConfigRepository(impl: RemoteConfigRepositoryImpl): RemoteConfigRepository
}
