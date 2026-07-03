package com.actionverite.live.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.domain.model.ContentLimits
import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.Interest
import com.actionverite.live.ui.components.PrimaryButton
import java.time.LocalDate

/**
 * Navigation entry point for first-run profile setup. Collects the wizard state,
 * forwards the completion event, and delegates rendering to the stateless
 * [OnboardingScreen].
 */
@Composable
fun OnboardingRoute(
    onComplete: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                OnboardingEvent.Completed -> onComplete()
            }
        }
    }

    OnboardingScreen(
        uiState = uiState,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onLanguageChange = viewModel::onLanguageChange,
        onCountryChange = viewModel::onCountryChange,
        onBirthDateChange = viewModel::onBirthDateChange,
        onToggleInterest = viewModel::onToggleInterest,
        onAllowTruthChange = viewModel::onAllowTruthChange,
        onAllowDareChange = viewModel::onAllowDareChange,
        onAllowAdultChange = viewModel::onAllowAdultChange,
        onMaxDifficultyChange = viewModel::onMaxDifficultyChange,
        onBack = viewModel::onBack,
        onNext = viewModel::onNext,
        onErrorShown = viewModel::onErrorShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(
    uiState: OnboardingUiState,
    onDisplayNameChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onCountryChange: (String) -> Unit,
    onBirthDateChange: (LocalDate) -> Unit,
    onToggleInterest: (Interest) -> Unit,
    onAllowTruthChange: (Boolean) -> Unit,
    onAllowDareChange: (Boolean) -> Unit,
    onAllowAdultChange: (Boolean) -> Unit,
    onMaxDifficultyChange: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onErrorShown: () -> Unit,
) {
    val today = remember { LocalDate.now() }
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
                title = { Text(uiState.step.title()) },
                navigationIcon = {
                    if (!uiState.step.isFirst) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LinearProgressIndicator(
                progress = { (uiState.step.ordinal + 1f) / OnboardingStep.entries.size },
                modifier = Modifier.fillMaxWidth(),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (uiState.step) {
                    OnboardingStep.IDENTITY -> IdentityStep(
                        uiState = uiState,
                        onDisplayNameChange = onDisplayNameChange,
                        onLanguageChange = onLanguageChange,
                        onCountryChange = onCountryChange,
                    )

                    OnboardingStep.BIRTHDAY -> BirthdayStep(
                        uiState = uiState,
                        today = today,
                        onBirthDateChange = onBirthDateChange,
                    )

                    OnboardingStep.INTERESTS -> InterestsStep(
                        selected = uiState.interests,
                        onToggleInterest = onToggleInterest,
                    )

                    OnboardingStep.LIMITS -> LimitsStep(
                        uiState = uiState,
                        isMinor = uiState.isMinorOn(today),
                        onAllowTruthChange = onAllowTruthChange,
                        onAllowDareChange = onAllowDareChange,
                        onAllowAdultChange = onAllowAdultChange,
                        onMaxDifficultyChange = onMaxDifficultyChange,
                    )
                }
            }

            Column(modifier = Modifier.padding(24.dp)) {
                if (uiState.isSaving) {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    PrimaryButton(
                        text = if (uiState.step.isLast) "Finish" else "Continue",
                        enabled = uiState.canAdvance(today),
                        onClick = onNext,
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityStep(
    uiState: OnboardingUiState,
    onDisplayNameChange: (String) -> Unit,
    onLanguageChange: (String) -> Unit,
    onCountryChange: (String) -> Unit,
) {
    Text(
        "Pick a name others will see, plus your language and country.",
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = uiState.displayName,
        onValueChange = onDisplayNameChange,
        label = { Text("Pseudo") },
        singleLine = true,
        supportingText = { Text("At least ${OnboardingUiState.MIN_NAME_LENGTH} characters") },
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.languageCode,
        onValueChange = { onLanguageChange(it.lowercase().take(2)) },
        label = { Text("Language (ISO-639-1, e.g. en)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = uiState.countryCode,
        onValueChange = { onCountryChange(it.uppercase().take(2)) },
        label = { Text("Country (ISO-3166-1, e.g. US)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun BirthdayStep(
    uiState: OnboardingUiState,
    today: LocalDate,
    onBirthDateChange: (LocalDate) -> Unit,
) {
    Text(
        "Your date of birth tailors challenges to your age. Under-18 accounts are limited to safe content.",
        style = MaterialTheme.typography.bodyMedium,
    )
    // Lightweight ISO date entry; a full Material date picker can replace this later.
    DateFields(
        value = uiState.birthDate,
        today = today,
        onChange = onBirthDateChange,
    )
    if (uiState.birthDate != null && uiState.isMinorOn(today)) {
        Text(
            "You're under 18, so safe content limits will be applied automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Three numeric fields (year/month/day) that build a [LocalDate]. Kept dependency-free;
 * an invalid combination is simply ignored until all three form a real date.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateFields(
    value: LocalDate?,
    today: LocalDate,
    onChange: (LocalDate) -> Unit,
) {
    var year by remember { androidx.compose.runtime.mutableStateOf(value?.year?.toString().orEmpty()) }
    var month by remember { androidx.compose.runtime.mutableStateOf(value?.monthValue?.toString().orEmpty()) }
    var day by remember { androidx.compose.runtime.mutableStateOf(value?.dayOfMonth?.toString().orEmpty()) }

    fun tryEmit() {
        val y = year.toIntOrNull() ?: return
        val m = month.toIntOrNull() ?: return
        val d = day.toIntOrNull() ?: return
        if (y < 1900 || m !in 1..12 || d !in 1..31) return
        runCatching { LocalDate.of(y, m, d) }
            .getOrNull()
            ?.takeIf { !it.isAfter(today) }
            ?.let(onChange)
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = day,
            onValueChange = { day = it.filter(Char::isDigit).take(2); tryEmit() },
            label = { Text("Day") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = month,
            onValueChange = { month = it.filter(Char::isDigit).take(2); tryEmit() },
            label = { Text("Month") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        OutlinedTextField(
            value = year,
            onValueChange = { year = it.filter(Char::isDigit).take(4); tryEmit() },
            label = { Text("Year") },
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
            modifier = Modifier.weight(1.4f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun InterestsStep(
    selected: Set<Interest>,
    onToggleInterest: (Interest) -> Unit,
) {
    Text(
        "Choose what you're into. We use this to personalize challenges and matchmaking.",
        style = MaterialTheme.typography.bodyMedium,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Interest.entries.forEach { interest ->
            FilterChip(
                selected = interest in selected,
                onClick = { onToggleInterest(interest) },
                label = { Text(interest.label()) },
            )
        }
    }
}

@Composable
private fun LimitsStep(
    uiState: OnboardingUiState,
    isMinor: Boolean,
    onAllowTruthChange: (Boolean) -> Unit,
    onAllowDareChange: (Boolean) -> Unit,
    onAllowAdultChange: (Boolean) -> Unit,
    onMaxDifficultyChange: (Int) -> Unit,
) {
    Text(
        "Set your boundaries. You can change these any time in settings.",
        style = MaterialTheme.typography.bodyMedium,
    )
    ToggleRow("Allow Truth", uiState.allowTruth, onAllowTruthChange)
    ToggleRow("Allow Dare", uiState.allowDare, onAllowDareChange)
    ToggleRow(
        label = "Allow adult content (+18)",
        checked = uiState.allowAdult,
        onCheckedChange = onAllowAdultChange,
        enabled = !isMinor,
    )

    Spacer(Modifier.height(8.dp))
    Text("Max difficulty: ${uiState.maxDifficulty}", style = MaterialTheme.typography.titleSmall)
    val maxAllowed = if (isMinor) ContentLimits.MINOR_SAFE.maxDifficulty else Difficulty.MAX
    Slider(
        value = uiState.maxDifficulty.toFloat(),
        onValueChange = { onMaxDifficultyChange(it.toInt()) },
        valueRange = Difficulty.MIN.toFloat()..maxAllowed.toFloat(),
        steps = (maxAllowed - Difficulty.MIN - 1).coerceAtLeast(0),
    )

    if (isMinor) {
        AssistChip(
            onClick = {},
            enabled = false,
            label = { Text("Safe limits enforced for under-18") },
            colors = AssistChipDefaults.assistChipColors(),
        )
    }

    if (!uiState.allowTruth && !uiState.allowDare) {
        Text(
            "Keep at least one of Truth or Dare enabled to play.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Start,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

private fun OnboardingStep.title(): String = when (this) {
    OnboardingStep.IDENTITY -> "Create your profile"
    OnboardingStep.BIRTHDAY -> "Your birthday"
    OnboardingStep.INTERESTS -> "Your interests"
    OnboardingStep.LIMITS -> "Content limits"
}

/** Title-cased label for an [Interest] (e.g. SPORTS -> "Sports"). */
private fun Interest.label(): String =
    name.lowercase().replaceFirstChar { it.uppercase() }
