package com.actionverite.live.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.AuthProvider
import com.actionverite.live.domain.repository.AuthCredential
import com.actionverite.live.domain.repository.AuthRepository
import com.actionverite.live.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuthUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
)

/** One-shot navigation events (not part of the persistent UI state). */
sealed interface AuthEvent {
    data object NavigateHome : AuthEvent
    data object NavigateOnboarding : AuthEvent
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    private val _events = Channel<AuthEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onGoogleIdToken(idToken: String) =
        signIn(AuthCredential(provider = AuthProvider.GOOGLE, idToken = idToken))

    fun onFacebookToken(accessToken: String) =
        signIn(AuthCredential(provider = AuthProvider.FACEBOOK, accessToken = accessToken))

    fun onAppleIdToken(idToken: String) =
        signIn(AuthCredential(provider = AuthProvider.APPLE, idToken = idToken))

    fun signInWithEmail(email: String, password: String) =
        signIn(AuthCredential(provider = AuthProvider.EMAIL, email = email, password = password))

    fun continueAsGuest() = launchAuth { authRepository.signInAnonymously() }

    private fun signIn(credential: AuthCredential) = launchAuth { authRepository.signIn(credential) }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    private fun launchAuth(block: suspend () -> DomainResult<String>) {
        if (_uiState.value.isLoading) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = block()) {
                is DomainResult.Success -> routeAfterSignIn(result.data)
                is DomainResult.Failure -> _uiState.update {
                    it.copy(isLoading = false, error = result.error.message ?: "Sign-in failed")
                }
            }
        }
    }

    private suspend fun routeAfterSignIn(uid: String) {
        val profile = userRepository.getProfile(uid).getOrNull()
        val event = if (profile == null || profile.displayName.isBlank()) {
            AuthEvent.NavigateOnboarding
        } else {
            AuthEvent.NavigateHome
        }
        _uiState.update { it.copy(isLoading = false) }
        _events.send(event)
    }
}
