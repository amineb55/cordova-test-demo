package com.actionverite.live.feature.leaderboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.model.LeaderboardEntry
import com.actionverite.live.domain.model.LeaderboardScope
import com.actionverite.live.domain.repository.LeaderboardRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * UI state for the leaderboard screen.
 *
 * @property scope the currently selected ranking scope, mirrored by the [LeaderboardScope] tabs.
 * @property entries the ranked list for [scope]; emitted entries already carry [LeaderboardEntry.rank]
 *   and the [LeaderboardEntry.isCurrentUser] flag used to highlight the viewer's row.
 * @property isLoading true while the first emission for the active [scope] is pending.
 * @property error a human-readable message when the underlying stream fails, or null.
 */
data class LeaderboardUiState(
    val scope: LeaderboardScope = LeaderboardScope.GLOBAL,
    val entries: List<LeaderboardEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
)

/**
 * Drives the leaderboard screen across the three [LeaderboardScope]s required by the spec
 * ("classement mondial, national et entre amis").
 *
 * The selected scope is held in a [MutableStateFlow]; [flatMapLatest] re-subscribes to
 * [LeaderboardRepository.observe] whenever the user switches tabs, automatically cancelling
 * the previous scope's stream. Each scope switch first emits a loading state, then the ranked
 * list (or an error). The result is shared with [SharingStarted.WhileSubscribed] so the
 * underlying listener is released shortly after the screen leaves composition.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LeaderboardViewModel @Inject constructor(
    private val leaderboardRepository: LeaderboardRepository,
) : ViewModel() {

    private val selectedScope = MutableStateFlow(LeaderboardScope.GLOBAL)

    val uiState: StateFlow<LeaderboardUiState> = selectedScope
        .flatMapLatest { scope -> stateForScope(scope) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = LeaderboardUiState(),
        )

    /** Switches the active tab; the new scope's stream replaces the previous one. */
    fun onScopeSelected(scope: LeaderboardScope) {
        selectedScope.value = scope
    }

    /**
     * Builds the [LeaderboardUiState] stream for a single [scope], beginning with a loading
     * placeholder so tab switches feel responsive, and mapping failures to a recoverable error
     * state rather than crashing the collector.
     */
    private fun stateForScope(scope: LeaderboardScope) =
        leaderboardRepository.observe(scope)
            .map { entries ->
                LeaderboardUiState(scope = scope, entries = entries, isLoading = false)
            }
            .onStart { emit(LeaderboardUiState(scope = scope, isLoading = true)) }
            .catch { throwable ->
                emit(
                    LeaderboardUiState(
                        scope = scope,
                        isLoading = false,
                        error = throwable.message ?: "Failed to load leaderboard",
                    ),
                )
            }
}
