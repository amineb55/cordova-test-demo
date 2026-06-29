package com.actionverite.live.feature.matchmaking

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import com.actionverite.live.ui.components.ErrorState
import com.actionverite.live.ui.components.SecondaryButton
import java.util.concurrent.TimeUnit

/**
 * Navigation entry point for matchmaking. Forwards one-shot [MatchmakingEvent]s to the
 * navigation callbacks and delegates rendering to the stateless [MatchmakingScreen].
 */
@Composable
fun MatchmakingRoute(
    onMatched: (roomId: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: MatchmakingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is MatchmakingEvent.Matched -> onMatched(event.roomId)
                MatchmakingEvent.Cancelled -> onCancel()
            }
        }
    }

    MatchmakingScreen(
        uiState = uiState,
        onCancel = viewModel::cancel,
        onRetry = viewModel::retry,
    )
}

@Composable
private fun MatchmakingScreen(
    uiState: MatchmakingUiState,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
) {
    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            if (uiState.error != null) {
                ErrorState(message = uiState.error, onRetry = onRetry)
            } else {
                SearchingContent(
                    elapsedMs = uiState.elapsedMs,
                    poolSize = uiState.poolSize,
                    onCancel = onCancel,
                )
            }
        }
    }
}

@Composable
private fun SearchingContent(
    elapsedMs: Long,
    poolSize: Int,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        PulsingIndicator()

        Spacer(Modifier.height(32.dp))
        Text(
            text = "Searching for players…",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = formatElapsed(elapsedMs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (poolSize > 0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "$poolSize ${if (poolSize == 1) "player" else "players"} in the pool",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(48.dp))
        SecondaryButton(text = "Cancel", onClick = onCancel)
    }
}

/**
 * A circular progress indicator wrapped in a softly pulsing ring, giving the wait a
 * sense of motion. The ring scale/alpha animate forever via an infinite transition.
 */
@Composable
private fun PulsingIndicator() {
    val transition = rememberInfiniteTransition(label = "matchmaking-pulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-scale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_100, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-alpha",
    )

    Box(contentAlignment = Alignment.Center) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = alpha),
            modifier = Modifier
                .size(120.dp)
                .scale(scale),
        ) {}
        CircularProgressIndicator(modifier = Modifier.size(64.dp))
    }
}

/** Formats elapsed milliseconds as `M:SS`. */
private fun formatElapsed(elapsedMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedMs)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
