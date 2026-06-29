package com.actionverite.live.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.LevelProgress
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.RoomRepository
import com.actionverite.live.domain.repository.UserRepository
import com.actionverite.live.domain.usecase.progression.LevelSystem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Persistent state for the landing/lobby hub. */
data class HomeUiState(
    val isLoading: Boolean = true,
    val profile: UserProfile? = null,
    val levelProgress: LevelProgress? = null,
    /** Current contents of the private-room invite-code field. */
    val inviteCode: String = "",
    /** True while a [HomeViewModel.joinPrivateRoom] call is in flight. */
    val isJoining: Boolean = false,
    /** Transient error to surface (e.g. via snackbar) for a failed join. */
    val joinError: String? = null,
) {
    /** Whether the entered code is a plausible 6-char invite code. */
    val canJoin: Boolean get() = inviteCode.length == INVITE_CODE_LENGTH && !isJoining

    companion object {
        const val INVITE_CODE_LENGTH = 6
    }
}

/**
 * ViewModel for the HOME/LOBBY screen.
 *
 * It merges two concerns:
 *  - a *read* stream (the signed-in user's profile + derived [LevelProgress]),
 *    exposed reactively so the avatar/name/level bar stay live; and
 *  - an *action* layer (typing an invite code and joining a private room),
 *    held in a [MutableStateFlow] and combined into the same [uiState].
 *
 * Navigation is delivered through callback lambdas passed from the Route so the
 * feature stays decoupled from the navigation framework.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    userRepository: UserRepository,
    private val roomRepository: RoomRepository,
    private val levelSystem: LevelSystem,
) : ViewModel() {

    /** Mutable, action-driven slice of the state (code field + join status). */
    private val _actionState = MutableStateFlow(HomeUiState())

    val uiState: StateFlow<HomeUiState> =
        combine(userRepository.observeCurrentProfile(), _actionState) { profile, action ->
            action.copy(
                isLoading = false,
                profile = profile,
                levelProgress = profile?.let { levelSystem.progressFor(it.stats.xp) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    /**
     * Updates the invite-code field, keeping only alphanumeric characters,
     * upper-casing them and clamping to [HomeUiState.INVITE_CODE_LENGTH].
     */
    fun onInviteCodeChange(raw: String) {
        val sanitized = raw
            .filter(Char::isLetterOrDigit)
            .uppercase()
            .take(HomeUiState.INVITE_CODE_LENGTH)
        _actionState.update { it.copy(inviteCode = sanitized, joinError = null) }
    }

    /**
     * Attempts to join a private room using the current invite code. On success
     * the resolved [com.actionverite.live.domain.model.Room.id] is handed to
     * [onJoined]; on failure a human-readable message is parked in
     * [HomeUiState.joinError] for the UI to display.
     */
    fun joinPrivateRoom(onJoined: (String) -> Unit) {
        val code = _actionState.value.inviteCode
        if (code.length != HomeUiState.INVITE_CODE_LENGTH || _actionState.value.isJoining) return
        viewModelScope.launch {
            _actionState.update { it.copy(isJoining = true, joinError = null) }
            when (val result = roomRepository.joinByInviteCode(code)) {
                is DomainResult.Success -> {
                    _actionState.update { it.copy(isJoining = false, inviteCode = "") }
                    onJoined(result.data.id)
                }

                is DomainResult.Failure -> _actionState.update {
                    it.copy(
                        isJoining = false,
                        joinError = result.error.message ?: "Couldn't join that room.",
                    )
                }
            }
        }
    }

    /** Clears a surfaced join error once the UI has shown it. */
    fun onJoinErrorShown() = _actionState.update { it.copy(joinError = null) }
}
