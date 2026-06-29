package com.actionverite.live.ui.navigation

/** Type-safe-ish route catalogue for the app's navigation graph. */
object Destinations {
    const val AUTH = "auth"
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val MATCHMAKING = "matchmaking"
    const val FRIENDS = "friends"
    const val LEADERBOARD = "leaderboard"
    const val PROFILE = "profile"
    const val SETTINGS = "settings"
    const val WALLET = "wallet"

    const val ARG_ROOM_ID = "roomId"
    const val GAME = "game/{$ARG_ROOM_ID}"
    fun game(roomId: String) = "game/$roomId"

    const val RATING = "rating/{$ARG_ROOM_ID}"
    fun rating(roomId: String) = "rating/$roomId"
}
