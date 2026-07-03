package com.actionverite.live.feature.game

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.GamePhase
import com.actionverite.live.ui.components.ErrorState
import com.actionverite.live.ui.components.FullScreenLoading

/**
 * Navigation entry point for the GAME ROOM. [roomId] arrives from the nav arg and is
 * also read by the ViewModel from [androidx.lifecycle.SavedStateHandle]; [onLeave] is
 * invoked once the user has disconnected and left the room.
 */
@Composable
fun GameRoute(
    roomId: String,
    onLeave: () -> Unit,
    viewModel: GameViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()

    GameScreen(
        ui = ui,
        onChooseType = viewModel::chooseType,
        onVoteDifficulty = viewModel::castDifficultyVote,
        onSubmitOutcome = viewModel::submitOutcome,
        onToggleMic = viewModel::toggleMic,
        onToggleCamera = viewModel::toggleCamera,
        onSwitchCamera = viewModel::switchCamera,
        onLeave = { viewModel.leave(onLeave) },
        onErrorShown = viewModel::onErrorShown,
    )
}

@Composable
private fun GameScreen(
    ui: GameUiState,
    onChooseType: (ChallengeType) -> Unit,
    onVoteDifficulty: (Int) -> Unit,
    onSubmitOutcome: (Boolean) -> Unit,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onLeave: () -> Unit,
    onErrorShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(ui.error) {
        ui.error?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            GameControlBar(
                micEnabled = ui.micEnabled,
                cameraEnabled = ui.cameraEnabled,
                onToggleMic = onToggleMic,
                onToggleCamera = onToggleCamera,
                onSwitchCamera = onSwitchCamera,
                onLeave = onLeave,
            )
        },
    ) { padding ->
        when {
            ui.isLoading -> FullScreenLoading(Modifier.padding(padding))
            ui.layout == null -> ErrorState(
                message = "Waiting for players to join…",
                modifier = Modifier.padding(padding),
            )
            else -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp),
            ) {
                TurnHeader(ui = ui)
                Spacer(Modifier.height(12.dp))

                // The video wall takes the bulk of the screen.
                VideoGrid(
                    tiles = ui.tiles,
                    layout = ui.layout,
                    activeUid = ui.activeUid,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )

                Spacer(Modifier.height(12.dp))

                // The phase-dependent game panel sits below the grid.
                GamePanel(
                    ui = ui,
                    onChooseType = onChooseType,
                    onVoteDifficulty = onVoteDifficulty,
                    onSubmitOutcome = onSubmitOutcome,
                )
            }
        }
    }
}

// region — Turn header -------------------------------------------------------

@Composable
private fun TurnHeader(ui: GameUiState, modifier: Modifier = Modifier) {
    val subtitle = when (ui.phase) {
        GamePhase.LOBBY -> "Waiting to start…"
        GamePhase.FINISHED -> "Game over"
        else -> when {
            ui.isMyTurn -> "It's your turn"
            ui.activePlayerName != null -> "${ui.activePlayerName}'s turn"
            else -> "In progress"
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (ui.round > 0) {
                Text(
                    text = "Round ${ui.round} / ${ui.maxRounds}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// endregion

// region — Phase-dependent panel ---------------------------------------------

/**
 * Renders the interactive panel for the current [GamePhase]:
 *  - CHOOSING_TYPE: big TRUTH / DARE buttons for the active player.
 *  - COLLECTING_VOTES: a 1..5 difficulty selector for everyone *but* the active player.
 *  - GENERATING: a spinner while the AI builds the challenge.
 *  - PERFORMING: the challenge text, plus Done/Failed controls for the active player.
 *  - RATING / LOBBY / FINISHED: a passive status line.
 */
@Composable
private fun GamePanel(
    ui: GameUiState,
    onChooseType: (ChallengeType) -> Unit,
    onVoteDifficulty: (Int) -> Unit,
    onSubmitOutcome: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.large,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            when (ui.phase) {
                GamePhase.CHOOSING_TYPE ->
                    if (ui.isMyTurn) {
                        ChooseTypePanel(onChooseType = onChooseType)
                    } else {
                        StatusText("${ui.activePlayerName ?: "Player"} is choosing Truth or Dare…")
                    }

                GamePhase.COLLECTING_VOTES ->
                    if (ui.isMyTurn) {
                        StatusText("The others are voting on your difficulty…")
                    } else {
                        DifficultyVotePanel(onVote = onVoteDifficulty)
                    }

                GamePhase.GENERATING -> GeneratingPanel()

                GamePhase.PERFORMING -> PerformingPanel(
                    ui = ui,
                    onSubmitOutcome = onSubmitOutcome,
                )

                GamePhase.RATING -> StatusText("Wrapping up the turn…")
                GamePhase.LOBBY -> StatusText("Get ready — the game is about to start!")
                GamePhase.FINISHED -> StatusText("That's a wrap. Thanks for playing!")
            }
        }
    }
}

@Composable
private fun ChooseTypePanel(onChooseType: (ChallengeType) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Your turn — pick one",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BigChoiceButton(
                label = "TRUTH",
                container = MaterialTheme.colorScheme.secondary,
                content = MaterialTheme.colorScheme.onSecondary,
                onClick = { onChooseType(ChallengeType.TRUTH) },
                modifier = Modifier.weight(1f),
            )
            BigChoiceButton(
                label = "DARE",
                container = MaterialTheme.colorScheme.primary,
                content = MaterialTheme.colorScheme.onPrimary,
                onClick = { onChooseType(ChallengeType.DARE) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun BigChoiceButton(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(72.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
        )
    }
}

@Composable
private fun DifficultyVotePanel(onVote: (Int) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Rate the difficulty (1–5)",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (value in Difficulty.RANGE) {
                OutlinedButton(
                    onClick = { onVote(value) },
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp),
                ) {
                    Text(
                        text = value.toString(),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun GeneratingPanel() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Generating a challenge…",
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PerformingPanel(
    ui: GameUiState,
    onSubmitOutcome: (Boolean) -> Unit,
) {
    val challengeText = ui.session?.currentTurn?.challenge?.text
    val type = ui.session?.currentTurn?.type

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        type?.let {
            Text(
                text = if (it == ChallengeType.TRUTH) "TRUTH" else "DARE",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
        }
        Text(
            text = challengeText ?: "Preparing the challenge…",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )

        if (ui.isMyTurn) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { onSubmitOutcome(false) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Failed")
                }
                Button(
                    onClick = { onSubmitOutcome(true) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Done")
                }
            }
        } else {
            Spacer(Modifier.height(12.dp))
            StatusText("${ui.activePlayerName ?: "Player"} is performing…")
        }
    }
}

@Composable
private fun StatusText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        textAlign = TextAlign.Center,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// endregion

// region — Bottom control bar ------------------------------------------------

@Composable
private fun GameControlBar(
    micEnabled: Boolean,
    cameraEnabled: Boolean,
    onToggleMic: () -> Unit,
    onToggleCamera: () -> Unit,
    onSwitchCamera: () -> Unit,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlToggle(
                enabled = micEnabled,
                onIcon = Icons.Filled.Mic,
                offIcon = Icons.Filled.MicOff,
                onDescription = "Mute microphone",
                offDescription = "Unmute microphone",
                onClick = onToggleMic,
            )
            ControlToggle(
                enabled = cameraEnabled,
                onIcon = Icons.Filled.Videocam,
                offIcon = Icons.Filled.VideocamOff,
                onDescription = "Turn camera off",
                offDescription = "Turn camera on",
                onClick = onToggleCamera,
            )
            IconButton(onClick = onSwitchCamera) {
                Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera")
            }
            LeaveButton(onClick = onLeave)
        }
    }
}

@Composable
private fun ControlToggle(
    enabled: Boolean,
    onIcon: ImageVector,
    offIcon: ImageVector,
    onDescription: String,
    offDescription: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        colors = if (enabled) {
            IconButtonDefaults.filledIconButtonColors()
        } else {
            IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    ) {
        Icon(
            imageVector = if (enabled) onIcon else offIcon,
            contentDescription = if (enabled) onDescription else offDescription,
        )
    }
}

@Composable
private fun LeaveButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
    ) {
        Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Leave the room")
    }
}

// endregion
