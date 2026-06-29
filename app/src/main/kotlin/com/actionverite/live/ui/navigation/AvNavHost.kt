package com.actionverite.live.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.actionverite.live.feature.auth.AuthRoute
import com.actionverite.live.feature.friends.FriendsRoute
import com.actionverite.live.feature.game.GameRoute
import com.actionverite.live.feature.home.HomeRoute
import com.actionverite.live.feature.leaderboard.LeaderboardRoute
import com.actionverite.live.feature.matchmaking.MatchmakingRoute
import com.actionverite.live.feature.onboarding.OnboardingRoute
import com.actionverite.live.feature.profile.ProfileRoute
import com.actionverite.live.feature.settings.SettingsRoute

/**
 * The single navigation graph. Each feature exposes one entry composable
 * (`*Route`) with callback parameters, keeping features decoupled from the
 * navigation framework and from each other.
 */
@Composable
fun AvNavHost(
    startDestination: String,
    navController: NavHostController = rememberNavController(),
) {
    NavHost(navController = navController, startDestination = startDestination) {

        composable(Destinations.AUTH) {
            AuthRoute(
                onAuthenticated = {
                    navController.navigate(Destinations.HOME) {
                        popUpTo(Destinations.AUTH) { inclusive = true }
                    }
                },
                onNeedsOnboarding = {
                    navController.navigate(Destinations.ONBOARDING) {
                        popUpTo(Destinations.AUTH) { inclusive = true }
                    }
                },
            )
        }

        composable(Destinations.ONBOARDING) {
            OnboardingRoute(
                onComplete = {
                    navController.navigate(Destinations.HOME) {
                        popUpTo(Destinations.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(Destinations.HOME) {
            HomeRoute(
                onPlay = { navController.navigate(Destinations.MATCHMAKING) },
                onJoinRoom = { roomId -> navController.navigate(Destinations.game(roomId)) },
                onOpenFriends = { navController.navigate(Destinations.FRIENDS) },
                onOpenLeaderboard = { navController.navigate(Destinations.LEADERBOARD) },
                onOpenProfile = { navController.navigate(Destinations.PROFILE) },
            )
        }

        composable(Destinations.MATCHMAKING) {
            MatchmakingRoute(
                onMatched = { roomId ->
                    navController.navigate(Destinations.game(roomId)) {
                        popUpTo(Destinations.MATCHMAKING) { inclusive = true }
                    }
                },
                onCancel = { navController.popBackStack() },
            )
        }

        composable(
            route = Destinations.GAME,
            arguments = listOf(navArgument(Destinations.ARG_ROOM_ID) { type = NavType.StringType }),
        ) { entry ->
            val roomId = entry.arguments?.getString(Destinations.ARG_ROOM_ID).orEmpty()
            GameRoute(
                roomId = roomId,
                onLeave = {
                    navController.navigate(Destinations.HOME) {
                        popUpTo(Destinations.HOME) { inclusive = true }
                    }
                },
            )
        }

        composable(Destinations.FRIENDS) {
            FriendsRoute(onBack = { navController.popBackStack() })
        }

        composable(Destinations.LEADERBOARD) {
            LeaderboardRoute(onBack = { navController.popBackStack() })
        }

        composable(Destinations.PROFILE) {
            ProfileRoute(
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Destinations.SETTINGS) },
                onSignOut = {
                    navController.navigate(Destinations.AUTH) {
                        popUpTo(0) { inclusive = true }
                    }
                },
            )
        }

        composable(Destinations.SETTINGS) {
            SettingsRoute(onBack = { navController.popBackStack() })
        }
    }
}
