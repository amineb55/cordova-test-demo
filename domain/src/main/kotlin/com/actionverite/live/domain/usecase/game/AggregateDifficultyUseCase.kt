package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.model.Difficulty
import com.actionverite.live.domain.model.DifficultyResult
import com.actionverite.live.domain.model.DifficultyVote
import javax.inject.Inject
import kotlin.math.roundToInt

/**
 * Aggregates the difficulty votes cast by the *other* players for the active
 * player's dare, implementing: "les autres votent une difficulté de 1 à 5, la
 * moyenne est calculée".
 *
 * Robustness:
 *  - If a voter votes more than once, only their **last** vote counts.
 *  - The active player's own vote (if it somehow arrives) is ignored.
 *  - With no eligible votes, returns [DifficultyResult.NEUTRAL] (3).
 */
class AggregateDifficultyUseCase @Inject constructor() {

    operator fun invoke(
        votes: List<DifficultyVote>,
        activePlayerUid: String? = null,
    ): DifficultyResult {
        // Drop the active player's own vote, then keep the last vote per voter.
        val effective = votes
            .asSequence()
            .filter { it.voterUid != activePlayerUid }
            .associateBy { it.voterUid } // last write wins
            .values

        if (effective.isEmpty()) return DifficultyResult.NEUTRAL

        val average = effective.sumOf { it.value }.toDouble() / effective.size
        val target = Difficulty.clamp(average.roundToInt())
        return DifficultyResult(
            average = average,
            target = target,
            voteCount = effective.size,
        )
    }
}
