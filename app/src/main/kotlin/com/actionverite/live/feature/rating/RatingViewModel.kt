package com.actionverite.live.feature.rating

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.Player
import com.actionverite.live.domain.model.PlayerRating
import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.repository.AuthRepository
import com.actionverite.live.domain.repository.ReputationRepository
import com.actionverite.live.domain.repository.RoomRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The flattened state the rating screen renders: the roster of fellow players that
 * the local user can rate (everyone in the room except themselves) plus the score
 * the user has chosen for each, and the transient submission / error flags.
 */
data class RatingUiState(
    val isLoading: Boolean = true,
    /** Everyone in the room except the local user. */
    val players: List<Player> = emptyList(),
    /** targetUid -> chosen score (1..5); defaults to the mid score for each player. */
    val scores: Map<String, Int> = emptyMap(),
    val isSubmitting: Boolean = false,
    val error: String? = null,
)

/** One-shot signal that the rating flow is finished and the caller should navigate away. */
sealed interface RatingEvent {
    data object Done : RatingEvent
}

/**
 * Drives the post-game RATING screen. Responsibilities:
 *
 *  - Observes the room roster ([RoomRepository.observeRoom]) and exposes the other
 *    players (everyone except the local user, resolved via
 *    [AuthRepository.currentUserId]) as a rate-able list, each defaulting to the mid
 *    score [PlayerRating.MIN_SCORE]..[PlayerRating.MAX_SCORE].
 *  - Tracks the user's per-target score choices ([setScore], clamped to the valid
 *    range) in a local [scoreState] map merged into [uiState].
 *  - On [submit], builds one [PlayerRating] per rated player and forwards them via
 *    [ReputationRepository.submitRatings]. Completion is signalled through a one-shot
 *    [events] channel even on failure, so the user is never trapped on this screen.
 *  - [skip] completes immediately without submitting anything.
 *
 * The [SavedStateHandle] supplies the [roomId] navigation argument so the screen is
 * restored correctly across process death (mirrors `GameViewModel`).
 */
@HiltViewModel
class RatingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    roomRepository: RoomRepository,
    private val reputationRepository: ReputationRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    /** Navigation argument (`Destinations.ARG_ROOM_ID`); empty only on misuse. */
    private val roomId: String = savedStateHandle.get<String>(ARG_ROOM_ID).orEmpty()

    private val selfUid: String? = authRepository.currentUserId()

    /** Local, user-chosen scores keyed by target uid. */
    private val scoreState = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Transient, screen-level state (submission in flight, submit failures). */
    private val submittingState = MutableStateFlow(false)
    private val errorState = MutableStateFlow<String?>(null)

    private val _events = Channel<RatingEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val uiState: StateFlow<RatingUiState> = combine(
        roomRepository.observeRoom(roomId),
        scoreState,
        submittingState,
        errorState,
    ) { room, scores, isSubmitting, error ->
        buildState(room, scores, isSubmitting, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = RatingUiState(),
    )

    /** Sets the score for [targetUid], clamped to the valid [PlayerRating] range. */
    fun setScore(targetUid: String, score: Int) {
        val clamped = score.coerceIn(PlayerRating.MIN_SCORE, PlayerRating.MAX_SCORE)
        scoreState.update { it + (targetUid to clamped) }
    }

    /**
     * Submits one [PlayerRating] per rated player, then signals [RatingEvent.Done].
     * Completion is emitted on both success and failure so the user is never trapped.
     */
    fun submit() {
        if (submittingState.value) return
        val rater = selfUid
        if (rater == null) {
            errorState.value = "You must be signed in to rate players."
            complete()
            return
        }
        val state = uiState.value
        val ratings = state.players.map { player ->
            PlayerRating(
                raterUid = rater,
                targetUid = player.uid,
                score = state.scores[player.uid] ?: DEFAULT_SCORE,
            )
        }
        viewModelScope.launch {
            submittingState.value = true
            when (val result = reputationRepository.submitRatings(roomId, ratings)) {
                is DomainResult.Success -> Unit
                is DomainResult.Failure ->
                    errorState.value = result.error.message ?: "Could not submit your ratings."
            }
            submittingState.value = false
            // Don't trap the user: complete regardless of the outcome.
            complete()
        }
    }

    /** Skips rating entirely and completes the flow. */
    fun skip() = complete()

    private fun complete() {
        viewModelScope.launch { _events.send(RatingEvent.Done) }
    }

    private fun buildState(
        room: Room?,
        scores: Map<String, Int>,
        isSubmitting: Boolean,
        error: String?,
    ): RatingUiState {
        val others = room?.players.orEmpty().filter { it.uid != selfUid }
        // Default every un-chosen player to the mid score so a bare Submit is valid.
        val resolvedScores = others.associate { it.uid to (scores[it.uid] ?: DEFAULT_SCORE) }
        return RatingUiState(
            isLoading = room == null,
            players = others,
            scores = resolvedScores,
            isSubmitting = isSubmitting,
            error = error,
        )
    }

    private companion object {
        // Mirrors com.actionverite.live.ui.navigation.Destinations.ARG_ROOM_ID without
        // pulling a UI dependency into the ViewModel.
        const val ARG_ROOM_ID = "roomId"

        /** Neutral default rating applied before the user touches a star. */
        const val DEFAULT_SCORE = 3
    }
}
