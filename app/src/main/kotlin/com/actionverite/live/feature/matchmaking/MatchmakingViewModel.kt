package com.actionverite.live.feature.matchmaking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.model.MatchPreferences
import com.actionverite.live.domain.model.MatchProfile
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.domain.repository.MatchmakingRepository
import com.actionverite.live.domain.repository.MatchmakingState
import com.actionverite.live.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Persistent UI state for the matchmaking ("searching for players") screen.
 *
 * [elapsedMs] is mirrored from the most recent [MatchmakingState.Searching] so the
 * screen can render a live timer without owning its own clock, and [poolSize] gives
 * the user a sense that the queue is alive.
 */
data class MatchmakingUiState(
    val isSearching: Boolean = false,
    val elapsedMs: Long = 0L,
    val poolSize: Int = 0,
    val error: String? = null,
)

/** One-shot navigation events kept out of the persistent [MatchmakingUiState]. */
sealed interface MatchmakingEvent {
    data class Matched(val roomId: String) : MatchmakingEvent
    data object Cancelled : MatchmakingEvent
}

/**
 * Drives the matchmaking flow:
 *
 *  1. Reads the current [UserProfile] (first value of [UserRepository.observeCurrentProfile]),
 *     derives a compact [MatchProfile] from it, and enqueues with default [MatchPreferences].
 *  2. Collects the resulting `Flow<MatchmakingState>`, mapping each emission onto
 *     [MatchmakingUiState]; a [MatchmakingState.Found] becomes a one-shot
 *     [MatchmakingEvent.Matched] and a [MatchmakingState.Failed] surfaces an error + retry.
 *  3. Exposes [cancel], which cancels the queue server-side then emits
 *     [MatchmakingEvent.Cancelled] so the Route can pop back.
 *
 * Search is started automatically on construction; [retry] restarts a failed search.
 */
@HiltViewModel
class MatchmakingViewModel @Inject constructor(
    private val userRepository: UserRepository,
    private val matchmakingRepository: MatchmakingRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MatchmakingUiState())
    val uiState: StateFlow<MatchmakingUiState> = _uiState.asStateFlow()

    private val _events = Channel<MatchmakingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** The active queue collection, retained so [cancel]/[retry] can tear it down. */
    private var searchJob: Job? = null

    init {
        startSearch()
    }

    /** (Re)starts the search; safe to call while a search is already running. */
    fun retry() = startSearch()

    private fun startSearch() {
        searchJob?.cancel()
        _uiState.update {
            it.copy(isSearching = true, elapsedMs = 0L, poolSize = 0, error = null)
        }
        searchJob = viewModelScope.launch {
            val profile = userRepository.observeCurrentProfile().first()
            if (profile == null) {
                _uiState.update { it.copy(isSearching = false, error = "You must be signed in to play.") }
                return@launch
            }
            val self = profile.toMatchProfile()
            matchmakingRepository.enqueue(self, MatchPreferences())
                .catch { cause ->
                    _uiState.update {
                        it.copy(isSearching = false, error = cause.message ?: "Matchmaking failed.")
                    }
                }
                .collect { state -> handleState(state) }
        }
    }

    private suspend fun handleState(state: MatchmakingState) {
        when (state) {
            MatchmakingState.Idle ->
                _uiState.update { it.copy(isSearching = false) }

            is MatchmakingState.Searching ->
                _uiState.update {
                    it.copy(
                        isSearching = true,
                        elapsedMs = state.elapsedMs,
                        poolSize = state.poolSize,
                        error = null,
                    )
                }

            is MatchmakingState.Found -> {
                _uiState.update { it.copy(isSearching = false) }
                _events.send(MatchmakingEvent.Matched(state.roomId))
            }

            is MatchmakingState.Failed ->
                _uiState.update { it.copy(isSearching = false, error = state.reason) }
        }
    }

    /** Leaves the queue (best-effort) and signals the Route to navigate back. */
    fun cancel() {
        searchJob?.cancel()
        viewModelScope.launch {
            _uiState.update { it.copy(isSearching = false) }
            matchmakingRepository.cancel()
            _events.send(MatchmakingEvent.Cancelled)
        }
    }

    fun onErrorShown() = _uiState.update { it.copy(error = null) }

    /**
     * Projects the rich [UserProfile] down to the matchmaking signals. Age is derived
     * deterministically from [UserProfile.birthEpochDay] using today's epoch day, and
     * adult content is gated by the user's own [com.actionverite.live.domain.model.ContentLimits].
     */
    private fun UserProfile.toMatchProfile(): MatchProfile {
        val todayEpochDay = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
        return MatchProfile(
            uid = uid,
            languageCode = languageCode,
            countryCode = countryCode,
            age = ageOn(todayEpochDay),
            level = stats.level,
            interests = interests,
            allowAdult = limits.allowAdult,
        )
    }
}
