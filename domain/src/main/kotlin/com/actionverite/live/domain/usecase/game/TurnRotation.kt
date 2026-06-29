package com.actionverite.live.domain.usecase.game

/** Result of advancing the turn cursor: the next index and whether it wrapped. */
data class TurnAdvance(val index: Int, val wrapped: Boolean)

/**
 * Pure turn-order arithmetic. Skips players who are not currently present
 * (disconnected) and reports when the rotation wraps past the end of the order,
 * which the [GameStateMachine] uses to increment the round counter.
 */
object TurnRotation {

    /**
     * Finds the next present player's index after [fromIndex] (exclusive),
     * scanning cyclically. Returns [fromIndex] with `wrapped = false` if nobody
     * else is present.
     */
    fun advance(
        order: List<String>,
        fromIndex: Int,
        present: Set<String>,
    ): TurnAdvance {
        val n = order.size
        if (n == 0) return TurnAdvance(fromIndex, false)

        for (step in 1..n) {
            val idx = (fromIndex + step) % n
            if (order[idx] in present) {
                return TurnAdvance(index = idx, wrapped = (fromIndex + step) >= n)
            }
        }
        // Nobody (else) present.
        return TurnAdvance(fromIndex, false)
    }
}
