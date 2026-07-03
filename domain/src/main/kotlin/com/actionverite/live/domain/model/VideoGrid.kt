package com.actionverite.live.domain.model

/**
 * Describes how N video tiles (2–6 players) should be arranged. The presentation
 * layer renders [rows] x [columns], letting the last row be partially filled.
 * Computed by [com.actionverite.live.domain.usecase.game.ComputeVideoGridLayoutUseCase]
 * to satisfy the spec's "disposition automatique optimisée (2 à 6 joueurs)".
 */
data class VideoGridLayout(
    val playerCount: Int,
    val rows: Int,
    val columns: Int,
    val orientation: Orientation,
) {
    enum class Orientation { PORTRAIT, LANDSCAPE }

    /** Tile slots, in reading order. The last row may contain fewer than [columns]. */
    val cells: Int get() = rows * columns

    /** Number of tiles on a given zero-based row (handles a short final row). */
    fun tilesInRow(row: Int): Int {
        val remaining = playerCount - row * columns
        return remaining.coerceIn(0, columns)
    }
}
