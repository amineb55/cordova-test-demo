package com.actionverite.live.data.remote

/**
 * Single source of truth for Firestore collection/document paths and field
 * names. Centralizing them keeps the schema consistent across repositories and
 * the security rules (see firestore.rules / docs/FIRESTORE_SCHEMA.md).
 */
object FirestorePaths {
    const val USERS = "users"
    const val ROOMS = "rooms"
    const val MATCH_QUEUE = "matchQueue"
    const val LEADERBOARDS = "leaderboards"
    const val REPORTS = "reports"
    const val FRIEND_REQUESTS = "friendRequests"

    // Sub-collections.
    const val PLAYERS = "players"
    const val GAME = "game"          // rooms/{id}/game/state (single doc)
    const val GAME_STATE_DOC = "state"
    const val SIGNALS = "signals"    // rooms/{id}/signals/{peer} for WebRTC
    const val MESSAGES = "messages"  // rooms/{id}/messages/{msgId}
    const val FRIENDS = "friends"    // users/{uid}/friends/{friendUid}
    const val SERVED_CHALLENGES = "served" // users/{uid}/served/{sig}
    const val DM = "dm"              // users/{uid}/dm/{peer}/messages
    const val TRANSACTIONS = "transactions" // users/{uid}/transactions/{id} (Gold ledger, function-written)
    const val RATINGS = "ratings"   // rooms/{id}/ratings/{raterUid_targetUid} (peer ratings)

    // Common field names referenced in queries.
    const val FIELD_XP = "xp"
    const val FIELD_LEVEL = "level"
    const val FIELD_COUNTRY = "countryCode"
    const val FIELD_LANGUAGE = "languageCode"
    const val FIELD_VISIBILITY = "visibility"
    const val FIELD_STATUS = "status"
    const val FIELD_UPDATED_AT = "updatedAt"
}
