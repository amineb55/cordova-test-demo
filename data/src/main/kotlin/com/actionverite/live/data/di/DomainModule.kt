package com.actionverite.live.data.di

import com.actionverite.live.domain.usecase.moderation.ContentModerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides domain collaborators that need explicit construction (Hilt ignores
 * Kotlin default parameter values, so the [ContentModerator]'s lexicon must be
 * supplied here). All other use cases are plain `@Inject`-constructed.
 */
@Module
@InstallIn(SingletonComponent::class)
object DomainModule {

    @Provides
    @Singleton
    fun provideContentModerator(): ContentModerator =
        // The curated, localized word-lists are loaded from Remote Config in
        // production; the on-device moderator is a fast first pass only.
        ContentModerator(lexicon = emptyMap())
}
