package com.actionverite.live.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ChallengeCategory
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.NotificationRepository
import com.actionverite.live.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/**
 * Persistent UI state for the settings screen. All editable values are mirrored
 * here so the stateless [SettingsScreen] renders from a single source of truth and
 * the ViewModel can validate / age-gate before persisting.
 *
 * [isAdult] is computed from the loaded profile against today's date and gates the
 * adult-content toggle: a minor can never flip [allowAdult] on, mirroring the spec
 * requirement that "+18" is unavailable to minors.
 */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val allowTruth: Boolean = true,
    val allowDare: Boolean = true,
    val allowAdult: Boolean = false,
    val maxDifficulty: Int = Difficulty.MAX,
    val blockedCategories: Set<ChallengeCategory> = emptySet(),
    val interests: Set<Interest> = emptySet(),
    val friendInvitesEnabled: Boolean = true,
    val isAdult: Boolean = false,
    val isSaving: Boolean = false,
    val error: String? = null,
    val savedMessage: String? = null,
)

/**
 * Backs the settings screen.
 *
 * Responsibilities:
 *  - Preload the current values once from [UserRepository.observeCurrentProfile]
 *    (first non-null emission) and seed the editable form.
 *  - Edit content limits (truth/dare/adult switches, difficulty, blocked
 *    categories) and persist via [UserRepository.updateLimits]; edit interests via
 *    [UserRepository.updateInterests]; toggle friend-invite notifications via
 *    [NotificationRepository.setFriendInvitesEnabled].
 *  - Enforce the age gate: adult content is only writable for an adult, determined
 *    with [UserProfile.isAdultOn] against today (injected as a lambda for
 *    deterministic, testable age math).
 *
 * Each edit optimistically updates local state, then writes through to the
 * repository; a failed write surfaces an error and is not silently lost because the
 * underlying profile stream remains the canonical source on next open.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val notificationRepository: NotificationRepository,
) : ViewModel() {

    /** Overridable for tests; defaults to the device clock. */
    private val today: () -> LocalDate = { LocalDate.now() }

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // Seed from the first available profile snapshot, then stop collecting:
            // settings is an edit form, not a live mirror, so later remote changes
            // shouldn't clobber in-flight local edits.
            val profile = userRepository.observeCurrentProfile().first { it != null }
            seedFrom(profile)
        }
    }

    private fun seedFrom(profile: UserProfile?) {
        if (profile == null) {
            _uiState.update { it.copy(isLoading = false) }
            return
        }
        val limits = profile.limits
        _uiState.update {
            it.copy(
                isLoading = false,
                allowTruth = limits.allowTruth,
                allowDare = limits.allowDare,
                allowAdult = limits.allowAdult,
                maxDifficulty = limits.maxDifficulty,
                blockedCategories = limits.blockedCategories,
                interests = profile.interests,
                isAdult = profile.isAdultOn(today().toEpochDay()),
            )
        }
    }

    fun onAllowTruthChange(allow: Boolean) {
        _uiState.update { it.copy(allowTruth = allow) }
        persistLimits()
    }

    fun onAllowDareChange(allow: Boolean) {
        _uiState.update { it.copy(allowDare = allow) }
        persistLimits()
    }

    /** Adult content can never be enabled for a non-adult, regardless of the toggle. */
    fun onAllowAdultChange(allow: Boolean) {
        val state = _uiState.value
        if (!state.isAdult && allow) return
        _uiState.update { it.copy(allowAdult = allow) }
        persistLimits()
    }

    fun onMaxDifficultyChange(value: Int) {
        _uiState.update { it.copy(maxDifficulty = Difficulty.clamp(value)) }
    }

    /** Slider edits persist on release to avoid a write per intermediate step. */
    fun onMaxDifficultyCommitted() = persistLimits()

    fun onToggleBlockedCategory(category: ChallengeCategory) {
        _uiState.update { state ->
            val next = if (category in state.blockedCategories) {
                state.blockedCategories - category
            } else {
                state.blockedCategories + category
            }
            state.copy(blockedCategories = next)
        }
        persistLimits()
    }

    fun onToggleInterest(interest: Interest) {
        val next = _uiState.updateAndGetInterests(interest)
        viewModelScope.launch {
            handle(userRepository.updateInterests(next))
        }
    }

    fun onFriendInvitesEnabledChange(enabled: Boolean) {
        _uiState.update { it.copy(friendInvitesEnabled = enabled) }
        viewModelScope.launch {
            handle(notificationRepository.setFriendInvitesEnabled(enabled))
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    fun onSavedMessageShown() = _uiState.update { it.copy(savedMessage = null) }

    private fun persistLimits() {
        val state = _uiState.value
        val limits = ContentLimits(
            allowTruth = state.allowTruth,
            allowDare = state.allowDare,
            // Defensive: never write adult=true for a non-adult even if state drifted.
            allowAdult = state.allowAdult && state.isAdult,
            maxDifficulty = Difficulty.clamp(state.maxDifficulty),
            blockedCategories = state.blockedCategories,
        )
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            handle(userRepository.updateLimits(limits))
        }
    }

    /** Updates the interest set in state and returns the new value for persistence. */
    private fun MutableStateFlow<SettingsUiState>.updateAndGetInterests(interest: Interest): Set<Interest> {
        var next: Set<Interest> = emptySet()
        update { state ->
            next = if (interest in state.interests) {
                state.interests - interest
            } else {
                state.interests + interest
            }
            state.copy(interests = next)
        }
        return next
    }

    private fun handle(result: DomainResult<Unit>) {
        _uiState.update {
            when (result) {
                is DomainResult.Success -> it.copy(isSaving = false, savedMessage = "Saved")
                is DomainResult.Failure -> it.copy(
                    isSaving = false,
                    error = result.error.message ?: "Could not save your settings.",
                )
            }
        }
    }
}
