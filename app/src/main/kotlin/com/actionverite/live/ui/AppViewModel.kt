package com.actionverite.live.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.repository.AuthRepository
import com.actionverite.live.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Top-level app state used to choose the navigation start destination. */
sealed interface RootState {
    data object Loading : RootState
    data object Unauthenticated : RootState
    data object NeedsOnboarding : RootState
    data object Ready : RootState
}

@HiltViewModel
class AppViewModel @Inject constructor(
    authRepository: AuthRepository,
    userRepository: UserRepository,
) : ViewModel() {

    @OptIn(ExperimentalCoroutinesApi::class)
    val rootState: StateFlow<RootState> = authRepository.authState
        .flatMapLatest { uid ->
            if (uid == null) {
                flowOf(RootState.Unauthenticated)
            } else {
                userRepository.observeCurrentProfile().map { profile ->
                    // A profile with a chosen display name marks onboarding complete.
                    if (profile == null || profile.displayName.isBlank()) RootState.NeedsOnboarding
                    else RootState.Ready
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RootState.Loading)
}
