package com.actionverite.live.feature.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.Friend
import com.actionverite.live.domain.model.FriendRequest
import com.actionverite.live.domain.model.Presence
import com.actionverite.live.ui.components.Avatar

/** Navigation entry point for the Friends screen. */
@Composable
fun FriendsRoute(
    onBack: () -> Unit,
    viewModel: FriendsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    FriendsScreen(
        uiState = uiState,
        onBack = onBack,
        onSelectTab = viewModel::selectTab,
        onSearchQueryChange = viewModel::onSearchQueryChange,
        onSearch = viewModel::search,
        onSendRequest = viewModel::sendRequest,
        onAcceptRequest = viewModel::acceptRequest,
        onDeclineRequest = viewModel::declineRequest,
        onErrorShown = viewModel::onErrorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FriendsScreen(
    uiState: FriendsUiState,
    onBack: () -> Unit,
    onSelectTab: (FriendsTab) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSendRequest: (String) -> Unit,
    onAcceptRequest: (String) -> Unit,
    onDeclineRequest: (String) -> Unit,
    onErrorShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Friends") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            val tabs = FriendsTab.entries
            TabRow(selectedTabIndex = uiState.selectedTab.ordinal) {
                tabs.forEach { tab ->
                    Tab(
                        selected = uiState.selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        text = { Text(tab.label(requestCount = uiState.incomingRequests.size)) },
                    )
                }
            }

            when (uiState.selectedTab) {
                FriendsTab.FRIENDS -> FriendsList(uiState.friends)
                FriendsTab.REQUESTS -> RequestsList(
                    requests = uiState.incomingRequests,
                    onAccept = onAcceptRequest,
                    onDecline = onDeclineRequest,
                )
                FriendsTab.SEARCH -> SearchSection(
                    query = uiState.searchQuery,
                    isSearching = uiState.isSearching,
                    results = uiState.searchResults,
                    pendingRequestUids = uiState.pendingRequestUids,
                    onQueryChange = onSearchQueryChange,
                    onSearch = onSearch,
                    onSendRequest = onSendRequest,
                )
            }
        }
    }
}

private fun FriendsTab.label(requestCount: Int): String = when (this) {
    FriendsTab.FRIENDS -> "Friends"
    FriendsTab.REQUESTS -> if (requestCount > 0) "Requests ($requestCount)" else "Requests"
    FriendsTab.SEARCH -> "Add"
}

@Composable
private fun FriendsList(friends: List<Friend>) {
    if (friends.isEmpty()) {
        EmptyState("No friends yet. Use the Add tab to find people.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(friends, key = { it.uid }) { friend ->
            FriendRow(friend)
        }
    }
}

@Composable
private fun FriendRow(friend: Friend) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box {
            Avatar(photoUrl = friend.photoUrl, size = 48)
            PresenceDot(
                presence = friend.presence,
                modifier = Modifier.align(Alignment.BottomEnd),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(friend.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "Level ${friend.level} • ${friend.presence.label()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RequestsList(
    requests: List<FriendRequest>,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    if (requests.isEmpty()) {
        EmptyState("No pending requests.")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(requests, key = { it.id }) { request ->
            RequestRow(request = request, onAccept = onAccept, onDecline = onDecline)
        }
    }
}

@Composable
private fun RequestRow(
    request: FriendRequest,
    onAccept: (String) -> Unit,
    onDecline: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Avatar(photoUrl = null, size = 48)
        Spacer(Modifier.width(12.dp))
        Text(
            request.fromDisplayName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onAccept(request.id) }) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "Accept",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        IconButton(onClick = { onDecline(request.id) }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Decline",
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchSection(
    query: String,
    isSearching: Boolean,
    results: List<Friend>,
    pendingRequestUids: Set<String>,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onSendRequest: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            label = { Text("Search by name") },
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                IconButton(onClick = onSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
        )

        when {
            isSearching -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            results.isEmpty() && query.isNotBlank() -> EmptyState("No users found.")
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(results, key = { it.uid }) { user ->
                    SearchResultRow(
                        user = user,
                        isPending = user.uid in pendingRequestUids,
                        onSendRequest = onSendRequest,
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(
    user: Friend,
    isPending: Boolean,
    onSendRequest: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Avatar(photoUrl = user.photoUrl, size = 48)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                "Level ${user.level}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isPending) {
            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
        } else {
            IconButton(onClick = { onSendRequest(user.uid) }) {
                Icon(Icons.Filled.PersonAdd, contentDescription = "Add friend")
            }
        }
    }
}

/** Small colored dot overlaid on an avatar to indicate presence. */
@Composable
private fun PresenceDot(presence: Presence, modifier: Modifier = Modifier) {
    Surface(
        color = presence.color(),
        shape = CircleShape,
        border = androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.surface),
        modifier = modifier.size(14.dp).clip(CircleShape),
    ) {}
}

@Composable
private fun EmptyState(message: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Presence.label(): String = when (this) {
    Presence.ONLINE -> "Online"
    Presence.IN_GAME -> "In game"
    Presence.AWAY -> "Away"
    Presence.OFFLINE -> "Offline"
}

@Composable
private fun Presence.color(): Color = when (this) {
    Presence.ONLINE -> Color(0xFF4CAF50)
    Presence.IN_GAME -> MaterialTheme.colorScheme.primary
    Presence.AWAY -> Color(0xFFFFB300)
    Presence.OFFLINE -> MaterialTheme.colorScheme.outline
}
