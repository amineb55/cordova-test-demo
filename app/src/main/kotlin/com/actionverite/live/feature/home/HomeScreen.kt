package com.actionverite.live.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.UserProfile
import com.actionverite.live.ui.components.Avatar
import com.actionverite.live.ui.components.FullScreenLoading
import com.actionverite.live.ui.components.PrimaryButton

/** Navigation entry point for the home/lobby hub. */
@Composable
fun HomeRoute(
    onPlay: () -> Unit,
    onJoinRoom: (String) -> Unit,
    onOpenFriends: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenProfile: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    HomeScreen(
        uiState = uiState,
        onPlay = onPlay,
        onInviteCodeChange = viewModel::onInviteCodeChange,
        onJoinRoom = { viewModel.joinPrivateRoom(onJoinRoom) },
        onJoinErrorShown = viewModel::onJoinErrorShown,
        onOpenFriends = onOpenFriends,
        onOpenLeaderboard = onOpenLeaderboard,
        onOpenProfile = onOpenProfile,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreen(
    uiState: HomeUiState,
    onPlay: () -> Unit,
    onInviteCodeChange: (String) -> Unit,
    onJoinRoom: () -> Unit,
    onJoinErrorShown: () -> Unit,
    onOpenFriends: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.joinError) {
        uiState.joinError?.let {
            snackbar.showSnackbar(it)
            onJoinErrorShown()
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Action ou Vérité Live") }) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        when {
            uiState.isLoading -> FullScreenLoading(Modifier.padding(padding))
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileHeader(
                    profile = uiState.profile,
                    levelFraction = uiState.levelProgress?.fraction ?: 0f,
                    levelLabel = uiState.levelProgress?.let { "Level ${it.level}" } ?: "Level 1",
                    onClick = onOpenProfile,
                )

                Spacer(Modifier.height(8.dp))

                PrimaryButton(text = "Play", onClick = onPlay)

                JoinRoomCard(
                    code = uiState.inviteCode,
                    canJoin = uiState.canJoin,
                    isJoining = uiState.isJoining,
                    onCodeChange = onInviteCodeChange,
                    onJoin = onJoinRoom,
                )

                QuickLinksRow(
                    onOpenFriends = onOpenFriends,
                    onOpenLeaderboard = onOpenLeaderboard,
                    onOpenProfile = onOpenProfile,
                )
            }
        }
    }
}

/** Tappable header showing avatar, name and a level progress bar. */
@Composable
private fun ProfileHeader(
    profile: UserProfile?,
    levelFraction: Float,
    levelLabel: String,
    onClick: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Avatar(photoUrl = profile?.photoUrl, size = 56)
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = profile?.displayName?.ifBlank { "Player" } ?: "Player",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(levelLabel, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { levelFraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** Private-room entry: a 6-char code field and a join button. */
@Composable
private fun JoinRoomCard(
    code: String,
    canJoin: Boolean,
    isJoining: Boolean,
    onCodeChange: (String) -> Unit,
    onJoin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Join a private room", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = code,
                onValueChange = onCodeChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Invite code") },
                placeholder = { Text("ABC123") },
                supportingText = { Text("6-character code") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters,
                ),
            )
            OutlinedButton(
                onClick = onJoin,
                enabled = canJoin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (isJoining) "Joining…" else "Join")
            }
        }
    }
}

/** Three equal-width shortcuts to friends, leaderboard and profile. */
@Composable
private fun QuickLinksRow(
    onOpenFriends: () -> Unit,
    onOpenLeaderboard: () -> Unit,
    onOpenProfile: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        QuickLink(
            icon = Icons.Filled.Group,
            label = "Friends",
            onClick = onOpenFriends,
            modifier = Modifier.weight(1f),
        )
        QuickLink(
            icon = Icons.Filled.EmojiEvents,
            label = "Ranking",
            onClick = onOpenLeaderboard,
            modifier = Modifier.weight(1f),
        )
        QuickLink(
            icon = Icons.Filled.Person,
            label = "Profile",
            onClick = onOpenProfile,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickLink(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier, onClick = onClick) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null)
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}
