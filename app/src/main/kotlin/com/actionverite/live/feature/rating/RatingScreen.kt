package com.actionverite.live.feature.rating

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.Player
import com.actionverite.live.domain.model.PlayerRating
import com.actionverite.live.ui.components.Avatar
import com.actionverite.live.ui.components.FullScreenLoading
import com.actionverite.live.ui.components.PrimaryButton

@Composable
fun RatingRoute(
    roomId: String,
    onDone: () -> Unit,
    viewModel: RatingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                RatingEvent.Done -> onDone()
            }
        }
    }

    RatingScreen(
        uiState = uiState,
        onSetScore = viewModel::setScore,
        onSubmit = viewModel::submit,
        onSkip = viewModel::skip,
    )
}

@Composable
private fun RatingScreen(
    uiState: RatingUiState,
    onSetScore: (targetUid: String, score: Int) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    if (uiState.isLoading) {
        FullScreenLoading()
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Rate your fellow players",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(24.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            uiState.players.forEach { player ->
                PlayerRatingRow(
                    player = player,
                    score = uiState.scores[player.uid] ?: PlayerRating.MIN_SCORE,
                    onSetScore = { score -> onSetScore(player.uid, score) },
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        PrimaryButton(
            text = "Submit",
            onClick = onSubmit,
            enabled = !uiState.isSubmitting,
        )

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick = onSkip,
            enabled = !uiState.isSubmitting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Skip")
        }
    }
}

@Composable
private fun PlayerRatingRow(
    player: Player,
    score: Int,
    onSetScore: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(photoUrl = player.photoUrl, size = 48)
        Spacer(Modifier.size(12.dp))
        Text(
            text = player.displayName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        StarRating(score = score, onSetScore = onSetScore)
    }
}

@Composable
private fun StarRating(
    score: Int,
    onSetScore: (Int) -> Unit,
) {
    Row {
        for (star in PlayerRating.MIN_SCORE..PlayerRating.MAX_SCORE) {
            val selected = star <= score
            IconButton(onClick = { onSetScore(star) }) {
                Icon(
                    imageVector = if (selected) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = "Rate $star",
                    tint = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}
