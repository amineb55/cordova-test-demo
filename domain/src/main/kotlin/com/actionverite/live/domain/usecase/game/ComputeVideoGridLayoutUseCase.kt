package com.actionverite.live.domain.usecase.game

import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.model.VideoGridLayout
import com.actionverite.live.domain.model.VideoGridLayout.Orientation
import javax.inject.Inject

/**
 * Computes the optimized video tile arrangement for 2–6 players
 * ("disposition automatique optimisée"). The layout favours large, balanced
 * tiles for the device orientation and never leaves more than one short row.
 */
class ComputeVideoGridLayoutUseCase @Inject constructor() {

    operator fun invoke(
        playerCount: Int,
        orientation: Orientation = Orientation.PORTRAIT,
    ): VideoGridLayout {
        require(playerCount in Room.MIN_PLAYERS..Room.MAX_PLAYERS) {
            "playerCount must be in ${Room.MIN_PLAYERS}..${Room.MAX_PLAYERS}, was $playerCount"
        }

        val (rows, columns) = when (orientation) {
            Orientation.PORTRAIT -> when (playerCount) {
                2 -> 2 to 1
                3, 4 -> 2 to 2
                else -> 3 to 2 // 5, 6
            }
            Orientation.LANDSCAPE -> when (playerCount) {
                2 -> 1 to 2
                3 -> 1 to 3
                4 -> 2 to 2
                else -> 2 to 3 // 5, 6
            }
        }

        return VideoGridLayout(
            playerCount = playerCount,
            rows = rows,
            columns = columns,
            orientation = orientation,
        )
    }
}
