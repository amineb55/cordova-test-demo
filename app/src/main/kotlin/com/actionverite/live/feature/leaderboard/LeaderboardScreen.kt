package com.actionverite.live.feature.leaderboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.LeaderboardEntry
import com.actionverite.live.domain.model.LeaderboardScope
import com.actionverite.live.ui.components.Avatar
import com.actionverite.live.ui.components.ErrorState
import com.actionverite.live.ui.components.FullScreenLoading

/** Tab order, matching the spec: world / national / friends rankings. */
private val SCOPE_TABS = listOf(
    LeaderboardScope.GLOBAL,
    LeaderboardScope.NATIONAL,
    LeaderboardScope.FRIENDS,
)

private fun LeaderboardScope.label(): String = when (this) {
    LeaderboardScope.GLOBAL -> "Global"
    LeaderboardScope.NATIONAL -> "National"
    LeaderboardScope.FRIENDS -> "Friends"
}

@Composable
fun LeaderboardRoute(
    onBack: () -> Unit,
    viewModel: LeaderboardViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    LeaderboardScreen(
        uiState = uiState,
        onBack = onBack,
        onScopeSelected = viewModel::onScopeSelected,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LeaderboardScreen(
    uiState: LeaderboardUiState,
    onBack: () -> Unit,
    onScopeSelected: (LeaderboardScope) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Leaderboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = SCOPE_TABS.indexOf(uiState.scope).coerceAtLeast(0)) {
                SCOPE_TABS.forEach { scope ->
                    Tab(
                        selected = scope == uiState.scope,
                        onClick = { onScopeSelected(scope) },
                        text = { Text(scope.label()) },
                    )
                }
            }

            when {
                uiState.isLoading -> FullScreenLoading()
                uiState.error != null -> ErrorState(message = uiState.error)
                uiState.entries.isEmpty() -> ErrorState(message = "No ranking yet")
                else -> LeaderboardList(entries = uiState.entries)
            }
        }
    }
}

@Composable
private fun LeaderboardList(entries: List<LeaderboardEntry>) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items = entries, key = { it.uid }) { entry ->
            LeaderboardRow(entry = entry)
        }
    }
}

@Composable
private fun LeaderboardRow(entry: LeaderboardEntry) {
    // Highlight the viewer's own row so they can quickly spot their standing.
    val containerColor =
        if (entry.isCurrentUser) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surface
    val contentColor =
        if (entry.isCurrentUser) MaterialTheme.colorScheme.onPrimaryContainer
        else MaterialTheme.colorScheme.onSurface

    Surface(
        color = containerColor,
        contentColor = contentColor,
        tonalElevation = if (entry.isCurrentUser) 0.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "#${entry.rank}",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.widthIn(min = 44.dp),
            )
            Avatar(photoUrl = entry.photoUrl, size = 40)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "Level ${entry.level} • ${entry.xp} XP",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
