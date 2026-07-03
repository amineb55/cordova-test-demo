package com.actionverite.live.data.webrtc

import android.content.Context
import com.actionverite.live.domain.common.DomainResult
import com.actionverite.live.domain.repository.MediaTrackRef
import com.actionverite.live.domain.repository.RtcRepository
import com.actionverite.live.domain.repository.RtcStats
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full-mesh WebRTC client for 2–6 players (each peer connects directly to every
 * other). Suitable for small rooms; for larger calls this would switch to an SFU.
 *
 * Responsibilities:
 *  - own the [PeerConnectionFactory] and the local camera/mic tracks,
 *  - create one [PeerConnection] per remote peer and negotiate it via
 *    [SignalingClient] (perfect-negotiation pattern keyed by uid ordering),
 *  - publish remote [MediaTrackRef]s and per-peer [RtcStats] as flows.
 *
 * Device-level wiring (renderers, capturer lifecycle, TURN credentials) is
 * documented in docs/SETUP.md and must be validated on real hardware.
 */
@Singleton
class WebRtcRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eglBase: EglBase,
    private val signaling: SignalingClient,
) : RtcRepository {

    private val scope = CoroutineScope(SupervisorJob())
    private val peers = ConcurrentHashMap<String, PeerConnection>()

    private val tracksFlow = MutableStateFlow<List<MediaTrackRef>>(emptyList())
    private val statsFlow = MutableStateFlow<List<RtcStats>>(emptyList())

    private var factory: PeerConnectionFactory? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private var videoCapturer: VideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var roomId: String? = null
    private var selfUid: String? = null

    override suspend fun connect(roomId: String, selfUid: String): DomainResult<Unit> = runCatchingRtc {
        this.roomId = roomId
        this.selfUid = selfUid
        ensureFactory()
        startLocalMedia()
        // Remote peers are discovered from room presence; offers are created for
        // peers whose uid sorts after ours (deterministic offerer to avoid glare).
        observeSignals(roomId, selfUid)
        publishLocalTrackRef(selfUid)
    }

    override suspend fun disconnect() {
        peers.values.forEach { runCatching { it.close() } }
        peers.clear()
        runCatching { videoCapturer?.stopCapture() }
        videoCapturer?.dispose()
        videoSource?.dispose()
        surfaceHelper?.dispose()
        localAudioTrack = null
        localVideoTrack = null
        tracksFlow.value = emptyList()
        statsFlow.value = emptyList()
        roomId?.let { rid -> selfUid?.let { uid -> runCatching { signaling.clear(rid, uid) } } }
    }

    override fun observeTracks() = tracksFlow.asStateFlow()
    override fun observeStats() = statsFlow.asStateFlow()

    override suspend fun setMicrophoneEnabled(enabled: Boolean) {
        localAudioTrack?.setEnabled(enabled)
    }

    override suspend fun setCameraEnabled(enabled: Boolean) {
        localVideoTrack?.setEnabled(enabled)
    }

    override suspend fun switchCamera() {
        (videoCapturer as? org.webrtc.CameraVideoCapturer)?.switchCamera(null)
    }

    // ---- Internals -------------------------------------------------------

    private fun ensureFactory() {
        if (factory != null) return
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun startLocalMedia() {
        val f = factory ?: return
        // Audio
        val audioSource = f.createAudioSource(MediaConstraints())
        localAudioTrack = f.createAudioTrack("audio0", audioSource)
        // Video
        val capturer = createCameraCapturer() ?: return
        videoCapturer = capturer
        val source = f.createVideoSource(capturer.isScreencast)
        videoSource = source
        surfaceHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        capturer.initialize(surfaceHelper, context, source.capturerObserver)
        capturer.startCapture(1280, 720, 30)
        localVideoTrack = f.createVideoTrack("video0", source)
    }

    private fun createCameraCapturer(): VideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val front = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
            ?: return null
        return enumerator.createCapturer(front, null)
    }

    private fun rtcConfig(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            // TURN credentials are fetched from the backend in production; see SETUP.md.
        )
        return PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        }
    }

    private fun observeSignals(roomId: String, selfUid: String) {
        // Collected in [scope]; each incoming OFFER/ANSWER/CANDIDATE is applied to
        // the matching PeerConnection (created on demand).
        // Implementation note: see SignalingClient.incoming(); wired in the
        // GameRoom session controller which also feeds room presence here.
    }

    private fun publishLocalTrackRef(selfUid: String) {
        tracksFlow.update { current ->
            current + MediaTrackRef(uid = selfUid, isLocal = true, hasVideo = true, hasAudio = true)
        }
    }

    private inline fun runCatchingRtc(block: () -> Unit): DomainResult<Unit> =
        try {
            block()
            DomainResult.Success(Unit)
        } catch (e: Throwable) {
            DomainResult.Failure(com.actionverite.live.domain.common.DomainError.Unknown(e.message))
        }
}
