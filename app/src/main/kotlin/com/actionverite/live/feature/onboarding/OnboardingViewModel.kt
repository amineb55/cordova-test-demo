package com.actionverite.live.feature.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.domain.model.UserProfile
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
import java.time.LocalDate
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * The discrete steps of first-run profile setup (spec: "Inscription").
 * The form is split so each screen stays focused; [values] is the linear order.
 */
enum class OnboardingStep {
    IDENTITY,   // pseudo + language + country
    BIRTHDAY,   // date of birth -> birthEpochDay
    INTERESTS,  // multi-select Interest enum
    LIMITS;     // truth/dare/adult toggles + difficulty (locked for minors)

    val isFirst: Boolean get() = ordinal == 0
    val isLast: Boolean get() = ordinal == entries.lastIndex

    fun next(): OnboardingStep = entries.getOrElse(ordinal + 1) { this }
    fun previous(): OnboardingStep = entries.getOrElse(ordinal - 1) { this }
}

/**
 * Persistent UI state for onboarding. All editable fields live here so the
 * stateless [OnboardingScreen] can render and the ViewModel can validate before
 * building the immutable [UserProfile] on submit.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.IDENTITY,
    val displayName: String = "",
    val languageCode: String = "en",
    val countryCode: String = "US",
    val birthDate: LocalDate? = null,
    val interests: Set<Interest> = emptySet(),
    val allowTruth: Boolean = true,
    val allowDare: Boolean = true,
    val allowAdult: Boolean = false,
    val maxDifficulty: Int = Difficulty.MAX,
    val isSaving: Boolean = false,
    val error: String? = null,
) {
    /** A picked birth date strictly under 18 years on [today] forces safe limits. */
    fun isMinorOn(today: LocalDate): Boolean {
        val birth = birthDate ?: return false
        return birth.plusYears(MIN_ADULT_AGE).isAfter(today)
    }

    /** The currently-active step may advance/submit only when its inputs are valid. */
    fun canAdvance(today: LocalDate): Boolean = when (step) {
        OnboardingStep.IDENTITY -> displayName.trim().length >= MIN_NAME_LENGTH
        OnboardingStep.BIRTHDAY -> birthDate != null && !birthDate.isAfter(today)
        OnboardingStep.INTERESTS -> true // optional; selecting interests is encouraged, not required
        OnboardingStep.LIMITS -> allowTruth || allowDare // at least one play mode must remain on
    }

    companion object {
        const val MIN_NAME_LENGTH = 3
        const val MIN_ADULT_AGE = 18L
    }
}

/** One-shot events that don't belong in the persistent state. */
sealed interface OnboardingEvent {
    data object Completed : OnboardingEvent
}

/**
 * Drives the first-run profile setup wizard.
 *
 * Responsibilities:
 *  - Hold editable form state across the multi-step flow.
 *  - Enforce the age gate: a minor (under 18 on today's date) is locked to
 *    [ContentLimits.MINOR_SAFE] with adult content disabled, mirroring the spec
 *    requirement "+18 optionnel" being unavailable to minors.
 *  - On submit, assemble an immutable [UserProfile] (uid from [AuthRepository])
 *    and persist it via [UserRepository.createOrUpdateProfile]; emit
 *    [OnboardingEvent.Completed] on success.
 *
 * `today` is injected as a lambda so age math stays deterministic and testable.
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
) : ViewModel() {

    /** Overridable for tests; defaults to the device clock in UTC. */
    private val today: () -> LocalDate = { LocalDate.now() }

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    private val _events = Channel<OnboardingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    fun onDisplayNameChange(name: String) = _uiState.update { it.copy(displayName = name, error = null) }

    fun onLanguageChange(code: String) = _uiState.update { it.copy(languageCode = code) }

    fun onCountryChange(code: String) = _uiState.update { it.copy(countryCode = code) }

    /** Selecting a birth date re-applies the minor lock immediately for honest UI. */
    fun onBirthDateChange(date: LocalDate) = _uiState.update { state ->
        val minor = date.plusYears(OnboardingUiState.MIN_ADULT_AGE).isAfter(today())
        state.copy(
            birthDate = date,
            allowAdult = if (minor) false else state.allowAdult,
            maxDifficulty = if (minor) {
                state.maxDifficulty.coerceAtMost(ContentLimits.MINOR_SAFE.maxDifficulty)
            } else {
                state.maxDifficulty
            },
            error = null,
        )
    }

    fun onToggleInterest(interest: Interest) = _uiState.update { state ->
        val next = if (interest in state.interests) state.interests - interest else state.interests + interest
        state.copy(interests = next)
    }

    fun onAllowTruthChange(allow: Boolean) = _uiState.update { it.copy(allowTruth = allow) }

    fun onAllowDareChange(allow: Boolean) = _uiState.update { it.copy(allowDare = allow) }

    /** Adult content can never be enabled for a minor, regardless of the toggle. */
    fun onAllowAdultChange(allow: Boolean) = _uiState.update { state ->
        if (state.isMinorOn(today())) state else state.copy(allowAdult = allow)
    }

    fun onMaxDifficultyChange(value: Int) = _uiState.update { state ->
        val capped = if (state.isMinorOn(today())) {
            value.coerceAtMost(ContentLimits.MINOR_SAFE.maxDifficulty)
        } else {
            value
        }
        state.copy(maxDifficulty = capped.coerceIn(Difficulty.MIN, Difficulty.MAX))
    }

    fun onBack() = _uiState.update { it.copy(step = it.step.previous(), error = null) }

    /** Advances to the next step, or triggers submission when on the last step. */
    fun onNext() {
        val state = _uiState.value
        if (!state.canAdvance(today())) return
        if (state.step.isLast) submit() else _uiState.update { it.copy(step = it.step.next(), error = null) }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    /**
     * Builds and persists the [UserProfile]. Minors are coerced to
     * [ContentLimits.MINOR_SAFE]; adults get their chosen limits. Emits
     * [OnboardingEvent.Completed] only on a successful write.
     */
    private fun submit() {
        val state = _uiState.value
        if (state.isSaving) return

        val uid = authRepository.currentUserId()
        if (uid == null) {
            _uiState.update { it.copy(error = "You must be signed in to continue.") }
            return
        }

        val now = today()
        val minor = state.isMinorOn(now)
        val limits = if (minor) {
            ContentLimits.MINOR_SAFE
        } else {
            ContentLimits(
                allowTruth = state.allowTruth,
                allowDare = state.allowDare,
                allowAdult = state.allowAdult,
                maxDifficulty = state.maxDifficulty.coerceIn(Difficulty.MIN, Difficulty.MAX),
            )
        }

        val profile = UserProfile(
            uid = uid,
            displayName = state.displayName.trim(),
            languageCode = state.languageCode,
            countryCode = state.countryCode,
            birthEpochDay = state.birthDate?.toEpochDay(),
            interests = state.interests,
            limits = limits,
            createdAtEpochMs = now.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, error = null) }
            when (val result = userRepository.createOrUpdateProfile(profile)) {
                is DomainResult.Success -> {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.send(OnboardingEvent.Completed)
                }
                is DomainResult.Failure -> _uiState.update {
                    it.copy(isSaving = false, error = result.error.message ?: "Could not save your profile.")
                }
            }
        }
    }
}
