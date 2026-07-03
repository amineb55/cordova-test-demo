package com.actionverite.live.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.model.LevelProgress
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.AuthRepository
import com.actionverite.live.domain.repository.UserRepository
import com.actionverite.live.domain.usecase.progression.LevelSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val levelProgress: LevelProgress? = null,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    userRepository: UserRepository,
    levelSystem: LevelSystem,
) : ViewModel() {

    val uiState: StateFlow<ProfileUiState> = userRepository.observeCurrentProfile()
        .map { profile ->
            ProfileUiState(
                isLoading = false,
                profile = profile,
                levelProgress = profile?.let { levelSystem.progressFor(it.stats.xp) },
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileUiState())

    fun signOut(onSignedOut: () -> Unit) {
        viewModelScope.launch {
            authRepository.signOut()
            onSignedOut()
        }
    }
}
