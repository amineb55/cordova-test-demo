package com.actionverite.live.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.PlayerStats
import com.actionverite.live.ui.components.Avatar
import com.actionverite.live.ui.components.FullScreenLoading
import com.actionverite.live.ui.components.SecondaryButton

@Composable
fun ProfileRoute(
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    ProfileScreen(
        uiState = uiState,
        onBack = onBack,
        onOpenSettings = onOpenSettings,
        onSignOut = { viewModel.signOut(onSignOut) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileScreen(
    uiState: ProfileUiState,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        when {
            uiState.isLoading -> FullScreenLoading(Modifier.padding(padding))
            uiState.profile == null -> Text(
                "No profile",
                modifier = Modifier.padding(padding).padding(24.dp),
            )
            else -> {
                val profile = uiState.profile
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Avatar(photoUrl = profile.photoUrl, size = 96)
                    Spacer(Modifier.height(12.dp))
                    Text(profile.displayName, style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Level ${profile.stats.level} • ${profile.stats.xp} XP",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    uiState.levelProgress?.let { progress ->
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { progress.fraction },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                    StatsGrid(profile.stats)

                    Spacer(Modifier.height(32.dp))
                    SecondaryButton(text = "Sign out", onClick = onSignOut)
                }
            }
        }
    }
}

@Composable
private fun StatsGrid(stats: PlayerStats) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        StatRow("Games played", stats.gamesPlayed.toString())
        StatRow("Truths answered", stats.truthsAnswered.toString())
        StatRow("Dares completed", stats.daresCompleted.toString())
        StatRow("Badges", stats.unlockedBadges.size.toString())
        StatRow("Friends", stats.friendsCount.toString())
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
