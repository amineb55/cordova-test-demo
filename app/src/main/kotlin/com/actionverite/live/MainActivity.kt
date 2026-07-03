package com.actionverite.live

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.actionverite.live.ui.AppViewModel
import com.actionverite.live.ui.RootState
import com.actionverite.live.ui.components.FullScreenLoading
import com.actionverite.live.ui.navigation.AvNavHost
import com.actionverite.live.ui.navigation.Destinations
import com.actionverite.live.ui.theme.ActionVeriteTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val appViewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Hold the splash while we resolve the initial auth/onboarding state.
        var state: RootState = RootState.Loading
        splash.setKeepOnScreenCondition { state is RootState.Loading }

        setContent {
            val rootState by appViewModel.rootState.collectAsStateWithLifecycle()
            state = rootState

            ActionVeriteTheme {
                when (rootState) {
                    RootState.Loading -> FullScreenLoading()
                    else -> AvNavHost(startDestination = rootState.toStartDestination())
                }
            }
        }
    }

    private fun RootState.toStartDestination(): String = when (this) {
        RootState.Unauthenticated -> Destinations.AUTH
        RootState.NeedsOnboarding -> Destinations.ONBOARDING
        else -> Destinations.HOME
    }
}
