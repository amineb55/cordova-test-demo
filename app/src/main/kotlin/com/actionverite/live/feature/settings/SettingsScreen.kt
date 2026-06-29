package com.actionverite.live.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.ChallengeCategory
import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.ui.components.FullScreenLoading
import java.util.Locale

/** Navigation entry point for the settings screen. */
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(
        uiState = uiState,
        onBack = onBack,
        onAllowTruthChange = viewModel::onAllowTruthChange,
        onAllowDareChange = viewModel::onAllowDareChange,
        onAllowAdultChange = viewModel::onAllowAdultChange,
        onMaxDifficultyChange = viewModel::onMaxDifficultyChange,
        onMaxDifficultyCommitted = viewModel::onMaxDifficultyCommitted,
        onToggleBlockedCategory = viewModel::onToggleBlockedCategory,
        onToggleInterest = viewModel::onToggleInterest,
        onFriendInvitesEnabledChange = viewModel::onFriendInvitesEnabledChange,
        onErrorShown = viewModel::onErrorShown,
        onSavedMessageShown = viewModel::onSavedMessageShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    uiState: SettingsUiState,
    onBack: () -> Unit,
    onAllowTruthChange: (Boolean) -> Unit,
    onAllowDareChange: (Boolean) -> Unit,
    onAllowAdultChange: (Boolean) -> Unit,
    onMaxDifficultyChange: (Int) -> Unit,
    onMaxDifficultyCommitted: () -> Unit,
    onToggleBlockedCategory: (ChallengeCategory) -> Unit,
    onToggleInterest: (Interest) -> Unit,
    onFriendInvitesEnabledChange: (Boolean) -> Unit,
    onErrorShown: () -> Unit,
    onSavedMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }
    LaunchedEffect(uiState.savedMessage) {
        uiState.savedMessage?.let {
            snackbar.showSnackbar(it)
            onSavedMessageShown()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (uiState.isLoading) {
            FullScreenLoading(Modifier.padding(padding))
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Spacer(Modifier.height(8.dp))

            // --- Content limits ---------------------------------------------------
            SectionHeader("Content")
            SwitchRow(
                label = "Allow Truth",
                checked = uiState.allowTruth,
                onCheckedChange = onAllowTruthChange,
            )
            SwitchRow(
                label = "Allow Dare",
                checked = uiState.allowDare,
                onCheckedChange = onAllowDareChange,
            )
            SwitchRow(
                label = "Allow adult content (+18)",
                description = if (uiState.isAdult) {
                    "Mature challenges may appear during the game."
                } else {
                    "Only available for accounts aged 18 or over."
                },
                checked = uiState.allowAdult,
                enabled = uiState.isAdult,
                onCheckedChange = onAllowAdultChange,
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Max difficulty: ${uiState.maxDifficulty}",
                style = MaterialTheme.typography.bodyLarge,
            )
            Slider(
                value = uiState.maxDifficulty.toFloat(),
                onValueChange = { onMaxDifficultyChange(it.toInt()) },
                onValueChangeFinished = onMaxDifficultyCommitted,
                valueRange = Difficulty.MIN.toFloat()..Difficulty.MAX.toFloat(),
                // MAX-MIN intervals -> one tick per integer step.
                steps = (Difficulty.MAX - Difficulty.MIN) - 1,
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Blocked categories ----------------------------------------------
            SectionHeader("Blocked categories")
            Text(
                "Tap a category to block challenges from it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChallengeCategory.entries.forEach { category ->
                    FilterChip(
                        selected = category in uiState.blockedCategories,
                        onClick = { onToggleBlockedCategory(category) },
                        label = { Text(category.displayName()) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Interests --------------------------------------------------------
            SectionHeader("Interests")
            Text(
                "Used to personalize AI-generated challenges.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Interest.entries.forEach { interest ->
                    FilterChip(
                        selected = interest in uiState.interests,
                        onClick = { onToggleInterest(interest) },
                        label = { Text(interest.displayName()) },
                    )
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 12.dp))

            // --- Notifications ----------------------------------------------------
            SectionHeader("Notifications")
            SwitchRow(
                label = "Friend invites",
                description = "Get notified when a friend invites you to play.",
                checked = uiState.friendInvitesEnabled,
                onCheckedChange = onFriendInvitesEnabledChange,
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Title-cases an enum constant name for display, e.g. EMBARRASSING -> "Embarrassing". */
private fun ChallengeCategory.displayName(): String = name.titleCase()

private fun Interest.displayName(): String = name.titleCase()

private fun String.titleCase(): String =
    lowercase(Locale.getDefault()).replaceFirstChar {
        if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
    }
