package com.actionverite.live.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.R
import com.actionverite.live.ui.components.PrimaryButton
import com.actionverite.live.ui.components.SecondaryButton
import kotlinx.coroutines.launch

/** Navigation entry point for the auth screen. */
@Composable
fun AuthRoute(
    onAuthenticated: () -> Unit,
    onNeedsOnboarding: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AuthEvent.NavigateHome -> onAuthenticated()
                AuthEvent.NavigateOnboarding -> onNeedsOnboarding()
            }
        }
    }

    AuthScreen(
        uiState = uiState,
        onGoogleIdToken = viewModel::onGoogleIdToken,
        onGuest = viewModel::continueAsGuest,
        onErrorShown = viewModel::onErrorShown,
    )
}

@Composable
private fun AuthScreen(
    uiState: AuthUiState,
    onGoogleIdToken: (String) -> Unit,
    onGuest: () -> Unit,
    onErrorShown: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val googleClient = remember { GoogleAuthClient(context) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(uiState.error) {
        uiState.error?.let {
            snackbar.showSnackbar(it)
            onErrorShown()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResourceSafe(R.string.auth_title),
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResourceSafe(R.string.auth_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))

            PrimaryButton(
                text = stringResourceSafe(R.string.auth_continue_google),
                enabled = !uiState.isLoading,
                onClick = {
                    scope.launch {
                        runCatching { googleClient.getIdToken() }
                            .onSuccess(onGoogleIdToken)
                    }
                },
            )
            Spacer(Modifier.height(12.dp))
            SecondaryButton(
                text = stringResourceSafe(R.string.auth_guest),
                enabled = !uiState.isLoading,
                onClick = onGuest,
            )
        }
    }
}

// Small indirection so previews/tests don't require a Context-bound resource.
@Composable
private fun stringResourceSafe(id: Int): String = androidx.compose.ui.res.stringResource(id)
