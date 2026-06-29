package com.actionverite.live.feature.game

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.model.ChallengeType
import com.actionverite.live.domain.model.ConnectionQuality
import com.actionverite.live.domain.model.GamePhase
import com.actionverite.live.domain.model.GameSession
import com.actionverite.live.domain.model.Player
import com.actionverite.live.domain.model.Room
import com.actionverite.live.domain.model.VideoGridLayout
import com.actionverite.live.domain.repository.AuthRepository
import com.actionverite.live.domain.repository.GameRepository
import com.actionverite.live.domain.repository.MediaTrackRef
import com.actionverite.live.domain.repository.RoomRepository
import com.actionverite.live.domain.repository.RtcRepository
import com.actionverite.live.domain.repository.RtcStats
import com.actionverite.live.domain.usecase.game.ComputeVideoGridLayoutUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One video tile's full presentation state: the underlying realtime [player] plus
 * the resolved media-track handle and live RTC stats for that peer. The grid uses
 * [hasVideoTrack] to decide whether to attach a WebRTC renderer or fall back to the
 * player's [Avatar].
 */
data class PlayerTile(
    val player: Player,
    val track: MediaTrackRef? = null,
    val stats: RtcStats? = null,
    val isSelf: Boolean = false,
) {
    /** A video renderer should be attached only when the camera is on AND a track exists. */
    val hasVideoTrack: Boolean get() = player.cameraEnabled && track?.hasVideo == true

    /** Prefer the freshly-sampled RTC quality; fall back to the room-reported one. */
    val connection: ConnectionQuality get() = stats?.quality ?: player.connection
}

/**
 * The single, flattened state the game screen renders. Built by combining the room
 * roster, the authoritative [GameSession], the WebRTC track list and per-peer stats.
 */
data class GameUiState(
    val isLoading: Boolean = true,
    val roomId: String = "",
    val selfUid: String? = null,
    val room: Room? = null,
    val session: GameSession? = null,
    val tiles: List<PlayerTile> = emptyList(),
    val layout: VideoGridLayout? = null,
    /** Local mic/camera intent, mirrored to both WebRTC and the room media state. */
    val micEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val error: String? = null,
) {
    val phase: GamePhase get() = session?.phase ?: GamePhase.LOBBY

    /** The uid whose turn it currently is, if a game is in progress. */
    val activeUid: String? get() = session?.activeUid

    /** True when the local player is the one who must act in the current phase. */
    val isMyTurn: Boolean get() = selfUid != null && selfUid == activeUid

    val round: Int get() = session?.round ?: 0
    val maxRounds: Int get() = session?.maxRounds ?: GameSession.DEFAULT_MAX_ROUNDS

    /** Display name of the active player, for "C'est au tour de …" banners. */
    val activePlayerName: String?
        get() = tiles.firstOrNull { it.player.uid == activeUid }?.player?.displayName
}

/**
 * Drives the GAME ROOM — the centerpiece screen. Responsibilities:
 *
 *  - On init, joins the WebRTC session ([RtcRepository.connect]) for the local user.
 *  - Merges three realtime streams into one [GameUiState]: the room roster
 *    ([RoomRepository.observeRoom]), the authoritative game session
 *    ([GameRepository.observeSession]), and the live media tracks / stats
 *    ([RtcRepository.observeTracks] + [RtcRepository.observeStats]).
 *  - Computes the optimized 2–6 player tile arrangement via
 *    [ComputeVideoGridLayoutUseCase].
 *  - Exposes intent handlers for turn actions (choose Truth/Dare, vote difficulty,
 *    submit outcome) and media controls (mic, camera, switch camera, leave).
 *
 * The [SavedStateHandle] supplies the [roomId] navigation argument so the screen is
 * restored correctly across process death.
 */
@HiltViewModel
class GameViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val roomRepository: RoomRepository,
    private val gameRepository: GameRepository,
    private val rtcRepository: RtcRepository,
    private val authRepository: AuthRepository,
    private val computeVideoGridLayout: ComputeVideoGridLayoutUseCase,
) : ViewModel() {

    /** Navigation argument (`Destinations.ARG_ROOM_ID`); empty only on misuse. */
    private val roomId: String = savedStateHandle.get<String>(ARG_ROOM_ID).orEmpty()

    private val selfUid: String? = authRepository.currentUserId()

    /** Local media intent (also reflected to the room so peers see our state). */
    private val mediaState = MutableStateFlow(MediaIntent())

    /** Transient, screen-level error (action failures, connect failures). */
    private val errorState = MutableStateFlow<String?>(null)

    val uiState: StateFlow<GameUiState> = combine(
        roomRepository.observeRoom(roomId),
        gameRepository.observeSession(roomId),
        rtcRepository.observeTracks(),
        rtcRepository.observeStats(),
        combine(mediaState, errorState) { media, error -> media to error },
    ) { room, session, tracks, stats, (media, error) ->
        buildState(room, session, tracks, stats, media, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = GameUiState(roomId = roomId, selfUid = selfUid),
    )

    init {
        connect()
    }

    // region — Lifecycle / connection ----------------------------------------

    private fun connect() {
        val uid = selfUid ?: run {
            errorState.value = "You must be signed in to join a game."
            return
        }
        viewModelScope.launch {
            when (val result = rtcRepository.connect(roomId, uid)) {
                is DomainResult.Success -> Unit
                is DomainResult.Failure ->
                    errorState.value = result.error.message ?: "Could not join the video room."
            }
        }
    }

    // endregion

    // region — Media controls ------------------------------------------------

    /** Toggles the microphone on both the WebRTC track and the published room state. */
    fun toggleMic() {
        val enabled = !mediaState.value.mic
        mediaState.update { it.copy(mic = enabled) }
        viewModelScope.launch {
            rtcRepository.setMicrophoneEnabled(enabled)
            roomRepository.setMediaState(roomId, micEnabled = enabled, cameraEnabled = mediaState.value.camera)
        }
    }

    /** Toggles the camera on both the WebRTC track and the published room state. */
    fun toggleCamera() {
        val enabled = !mediaState.value.camera
        mediaState.update { it.copy(camera = enabled) }
        viewModelScope.launch {
            rtcRepository.setCameraEnabled(enabled)
            roomRepository.setMediaState(roomId, micEnabled = mediaState.value.mic, cameraEnabled = enabled)
        }
    }

    /** Flips between front and rear camera. */
    fun switchCamera() {
        viewModelScope.launch { rtcRepository.switchCamera() }
    }

    /**
     * Tears down the WebRTC session and leaves the room, then invokes [onLeft] so the
     * caller can navigate away. Navigation proceeds even if the leave call fails so the
     * user is never trapped in a broken room.
     */
    fun leave(onLeft: () -> Unit) {
        viewModelScope.launch {
            rtcRepository.disconnect()
            roomRepository.leaveRoom(roomId)
            onLeft()
        }
    }

    // endregion

    // region — Turn actions --------------------------------------------------

    /** Active player picks Vérité or Action during [GamePhase.CHOOSING_TYPE]. */
    fun chooseType(type: ChallengeType) = runAction { gameRepository.chooseType(roomId, type) }

    /** A non-active player casts a 1..5 difficulty vote during [GamePhase.COLLECTING_VOTES]. */
    fun castDifficultyVote(value: Int) = runAction { gameRepository.castDifficultyVote(roomId, value) }

    /** Active player reports whether they completed the challenge in [GamePhase.PERFORMING]. */
    fun submitOutcome(completed: Boolean) = runAction { gameRepository.submitOutcome(roomId, completed) }

    fun onErrorShown() {
        errorState.value = null
    }

    private fun runAction(block: suspend () -> DomainResult<Unit>) {
        viewModelScope.launch {
            when (val result = block()) {
                is DomainResult.Success -> Unit
                is DomainResult.Failure ->
                    errorState.value = result.error.message ?: "Action failed."
            }
        }
    }

    // endregion

    // region — State assembly ------------------------------------------------

    private fun buildState(
        room: Room?,
        session: GameSession?,
        tracks: List<MediaTrackRef>,
        stats: List<RtcStats>,
        media: MediaIntent,
        error: String?,
    ): GameUiState {
        val tracksByUid = tracks.associateBy { it.uid }
        val statsByUid = stats.associateBy { it.uid }

        val tiles = room?.players.orEmpty().map { player ->
            val isSelf = player.uid == selfUid
            // For the local player, reflect our own media intent immediately rather than
            // waiting for the round-trip through the room snapshot.
            val resolvedPlayer = if (isSelf) {
                player.copy(micEnabled = media.mic, cameraEnabled = media.camera)
            } else {
                player
            }
            PlayerTile(
                player = resolvedPlayer,
                track = tracksByUid[player.uid],
                stats = statsByUid[player.uid],
                isSelf = isSelf,
            )
        }

        val layout = tiles.size
            .takeIf { it in Room.MIN_PLAYERS..Room.MAX_PLAYERS }
            ?.let { computeVideoGridLayout(it) }

        return GameUiState(
            isLoading = room == null,
            roomId = roomId,
            selfUid = selfUid,
            room = room,
            session = session,
            tiles = tiles,
            layout = layout,
            micEnabled = media.mic,
            cameraEnabled = media.camera,
            error = error,
        )
    }

    // endregion

    /** Local, optimistic mic/camera intent kept independent of the room round-trip. */
    private data class MediaIntent(val mic: Boolean = true, val camera: Boolean = true)

    private companion object {
        // Mirrors com.actionverite.live.ui.navigation.Destinations.ARG_ROOM_ID without
        // pulling a UI dependency into the ViewModel.
        const val ARG_ROOM_ID = "roomId"
    }
}
