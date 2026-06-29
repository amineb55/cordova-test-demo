package com.actionverite.live.feature.friends

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Friend
import com.actionverite.live.domain.model.FriendRequest
import com.actionverite.live.domain.repository.FriendRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Sections the screen can surface, mirrored by the tab row. */
enum class FriendsTab { FRIENDS, REQUESTS, SEARCH }

/**
 * Persistent UI state for the Friends screen.
 *
 * [friends] and [incomingRequests] are sourced from the repository's hot Flows,
 * while the search-related fields are driven imperatively by user input.
 */
data class FriendsUiState(
    val isLoading: Boolean = true,
    val selectedTab: FriendsTab = FriendsTab.FRIENDS,
    val friends: List<Friend> = emptyList(),
    val incomingRequests: List<FriendRequest> = emptyList(),
    val searchQuery: String = "",
    val isSearching: Boolean = false,
    val searchResults: List<Friend> = emptyList(),
    /** UIDs with an in-flight [FriendRepository.sendRequest] call, for per-row spinners. */
    val pendingRequestUids: Set<String> = emptySet(),
    val error: String? = null,
)

/**
 * Drives the Friends feature: the accepted friends list, incoming requests with
 * accept/decline actions, and a user search with an "add friend" action.
 *
 * The friends list and incoming requests are exposed as a single [StateFlow]
 * combined from two repository Flows so the screen sees a consistent snapshot.
 * Imperative state (search input/results, in-flight requests, errors) lives in a
 * separate [MutableStateFlow] and is merged in via [combine]; this keeps the
 * reactive stream as the source of truth while still allowing one-off mutations.
 */
@HiltViewModel
class FriendsViewModel @Inject constructor(
    private val friendRepository: FriendRepository,
) : ViewModel() {

    /** Locally-owned, imperatively-mutated slice of the state. */
    private val _localState = MutableStateFlow(FriendsUiState())

    val uiState: StateFlow<FriendsUiState> = combine(
        friendRepository.observeFriends(),
        friendRepository.observeIncomingRequests(),
        _localState,
    ) { friends, requests, local ->
        local.copy(
            isLoading = false,
            friends = friends,
            incomingRequests = requests,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FriendsUiState())

    fun selectTab(tab: FriendsTab) {
        _localState.update { it.copy(selectedTab = tab) }
    }

    fun onSearchQueryChange(query: String) {
        _localState.update { it.copy(searchQuery = query) }
    }

    /** Runs a user search for the current query. Blank queries clear the results. */
    fun search() {
        val query = _localState.value.searchQuery.trim()
        if (query.isBlank()) {
            _localState.update { it.copy(searchResults = emptyList(), isSearching = false) }
            return
        }
        viewModelScope.launch {
            _localState.update { it.copy(isSearching = true, error = null) }
            when (val result = friendRepository.searchUsers(query)) {
                is DomainResult.Success -> _localState.update {
                    it.copy(isSearching = false, searchResults = result.data)
                }
                is DomainResult.Failure -> _localState.update {
                    it.copy(isSearching = false, error = result.error.message ?: "Search failed")
                }
            }
        }
    }

    /** Sends a friend request to [uid]; tracks the call so the row can show progress. */
    fun sendRequest(uid: String) {
        if (uid in _localState.value.pendingRequestUids) return
        viewModelScope.launch {
            _localState.update { it.copy(pendingRequestUids = it.pendingRequestUids + uid, error = null) }
            val result = friendRepository.sendRequest(uid)
            _localState.update { state ->
                state.copy(
                    pendingRequestUids = state.pendingRequestUids - uid,
                    error = (result as? DomainResult.Failure)?.error?.message
                        ?: if (result is DomainResult.Failure) "Could not send request" else state.error,
                )
            }
        }
    }

    fun acceptRequest(requestId: String) = runRequestAction { friendRepository.acceptRequest(requestId) }

    fun declineRequest(requestId: String) = runRequestAction { friendRepository.declineRequest(requestId) }

    fun onErrorShown() = _localState.update { it.copy(error = null) }

    private fun runRequestAction(block: suspend () -> DomainResult<Unit>) {
        viewModelScope.launch {
            when (val result = block()) {
                is DomainResult.Success -> Unit // observeIncomingRequests will refresh the list.
                is DomainResult.Failure -> _localState.update {
                    it.copy(error = result.error.message ?: "Action failed")
                }
            }
        }
    }
}
