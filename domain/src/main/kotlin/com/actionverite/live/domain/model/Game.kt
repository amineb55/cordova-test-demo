package com.actionverite.live.domain.model

/**
 * Lifecycle phases of a single turn. The progression is:
 * CHOOSING_TYPE → (DARE only) COLLECTING_VOTES → GENERATING → PERFORMING →
 * RATING → back to CHOOSING_TYPE for the next player, or FINISHED.
 */
enum class GamePhase {
    LOBBY,
    CHOOSING_TYPE,
    COLLECTING_VOTES,
    GENERATING,
    PERFORMING,
    RATING,
    FINISHED,
}

enum class RoundOutcome { PENDING, COMPLETED, SKIPPED, FAILED }

/** One player's turn within the game. Immutable; the state machine produces new copies. */
data class Turn(
    val index: Int,
    val activeUid: String,
    val type: ChallengeType? = null,
    val votes: List<DifficultyVote> = emptyList(),
    val difficulty: DifficultyResult? = null,
    val challenge: Challenge? = null,
    val outcome: RoundOutcome = RoundOutcome.PENDING,
)

/**
 * The authoritative, serializable snapshot of a game in progress. The
 * [GameStateMachine] use case is the only thing allowed to transition it.
 */
data class GameSession(
    val roomId: String,
    val phase: GamePhase = GamePhase.LOBBY,
    val turnOrder: List<String> = emptyList(),
    val activeIndex: Int = 0,
    val round: Int = 0,
    val maxRounds: Int = DEFAULT_MAX_ROUNDS,
    val currentTurn: Turn? = null,
    val history: List<Turn> = emptyList(),
    val startedAtEpochMs: Long = 0L,
) {
    val activeUid: String? get() = turnOrder.getOrNull(activeIndex)

    companion object {
        const val DEFAULT_MAX_ROUNDS = 10
    }
}
