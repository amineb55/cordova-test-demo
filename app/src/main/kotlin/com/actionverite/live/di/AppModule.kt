package com.actionverite.live.di

import com.actionverite.live.data.common.ForegroundActivityProvider
import com.actionverite.live.ui.ActivityTracker
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds app-module implementations needed by the data layer. */
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindForegroundActivityProvider(impl: ActivityTracker): ForegroundActivityProvider
}

/** Lets the [com.actionverite.live.ActionVeriteApp] obtain the singleton tracker to register it. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ActivityTrackerEntryPoint {
    fun activityTracker(): ActivityTracker
}
