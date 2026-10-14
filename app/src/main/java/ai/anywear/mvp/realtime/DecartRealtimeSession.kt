package ai.anywear.mvp.realtime

import ai.decart.sdk.AndroidLogger
import ai.decart.sdk.ConnectionState
import ai.decart.sdk.LogLevel
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.realtime.ConnectOptions
import ai.decart.sdk.realtime.FacingMode
import ai.decart.sdk.realtime.InitialPrompt
import ai.decart.sdk.realtime.RealTimeClient
import ai.decart.sdk.realtime.RealTimeClientConfig
import ai.decart.sdk.realtime.RealtimeMediaStream
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import livekit.org.webrtc.RendererCommon

/**
 * Thin lifecycle wrapper around Decart's official realtime SDK.
 *
 * The camera preview can be created before an API key is available. When the
 * user starts a try-on, the caller-owned preview stream is passed into the
 * RealTimeClient so preview and published video share one LiveKit room.
 */
class DecartRealtimeSession(
    private val context: Context,
    private val localContainer: FrameLayout,
    private val remoteContainer: FrameLayout,
    initialFacing: FacingMode = FacingMode.FRONT,
    private val onConnectionState: (ConnectionState) -> Unit = {},
    private val onRemoteStream: (Boolean) -> Unit = {},
    private val onError: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val collectorJobs = mutableListOf<Job>()
    private var client: RealTimeClient? = null
    private var localStream: RealtimeMediaStream? = null
    private var remoteStream: RealtimeMediaStream? = null
    private var localBinding: RendererBinding? = null
    private var remoteBinding: RendererBinding? = null
    private var previewModel: RealtimeModel? = null
    private var previewFacing: FacingMode? = null

    var facing: FacingMode = initialFacing
        private set

    val sessionId: String?
        get() = client?.sessionId

    fun setFacing(newFacing: FacingMode) {
        facing = newFacing
    }

    fun startPreview(model: RealtimeModel) {
        check(client == null) { "Cannot start preview while a session client is active" }
        if (localStream == null || previewModel?.name != model.name || previewFacing != facing) {
            disposeLocalStream()
            previewModel = model
            previewFacing = facing
            localStream = RealTimeClient.createLocalVideoStream(
                context = context,
                model = model,
                facing = facing,
                logger = AndroidLogger(LogLevel.WARN),
            )
        }
        val stream = localStream ?: error("Failed to create local camera stream")
        bindRenderer(RendererTarget.LOCAL, localContainer, stream, mirror = false)
        localContainer.visibility = View.VISIBLE
    }

    suspend fun connect(
        apiKey: String,
        baseUrl: String,
        model: RealtimeModel,
        imageBase64: String,
    ): RealtimeMediaStream {
        require(apiKey.isNotBlank()) { "Decart API Key is empty" }
        require(baseUrl.isNotBlank()) { "Decart realtime URL is empty" }

        if (localStream == null || previewModel?.name != model.name || previewFacing != facing) {
            disposeLocalStream()
            createPreviewAfterMissingStream(model)
        }
        val preview = requireNotNull(localStream) { "Failed to create local camera stream" }

        stopCollectors()
        releaseClientOnly()
        detachRenderer(remoteBinding)
        remoteBinding = null
        remoteStream = null
        onRemoteStream(false)
        remoteContainer.visibility = View.GONE

        val newClient = RealTimeClient(
            context = context,
            config = RealTimeClientConfig(
                apiKey = apiKey,
                baseUrl = baseUrl,
                logger = AndroidLogger(LogLevel.WARN),
            ),
        )
        client = newClient
        observe(newClient)

        return try {
            val connectedStream = newClient.connect(
                options = ConnectOptions(
                    model = model,
                    initialPrompt = InitialPrompt(
                        text = GARMENT_PROMPT,
                        enhance = true,
                    ),
                    initialImage = imageBase64,
                    facing = facing,
                    publishCamera = true,
                ),
                localStream = preview,
            )
            attachRemote(connectedStream)
            connectedStream
        } catch (error: Throwable) {
            Log.e(TAG, "Realtime connect failed", error)
            // stopSession may already have released this exact client while the
            // suspend call was unwinding; only clean up when it is still current.
            if (client === newClient) {
                stopCollectors()
                releaseClientOnly()
                // A caller-owned LiveKit room becomes terminal after disconnect,
                // so rebuild a clean preview after a failed/cancelled connect.
                runCatching {
                    disposeLocalStream()
                    startPreview(model)
                }
            }
            throw error
        }
    }

    fun stopSession(model: RealtimeModel?, restartPreview: Boolean) {
        stopCollectors()
        detachRenderer(remoteBinding)
        remoteBinding = null
        remoteStream = null
        onRemoteStream(false)
        remoteContainer.visibility = View.GONE

        releaseClientOnly()
        disposeLocalStream()

        if (restartPreview) {
            val targetModel = model ?: previewModel
            if (targetModel != null) runCatching { startPreview(targetModel) }
        }
    }

    fun stopPreview() {
        stopSession(previewModel, restartPreview = false)
        localContainer.visibility = View.GONE
        previewModel = null
    }

    fun release() {
        stopSession(previewModel, restartPreview = false)
        scope.cancel()
    }

    fun capture(container: FrameLayout): Bitmap? = runCatching {
        if (container.width <= 0 || container.height <= 0 || container.visibility != View.VISIBLE) {
            return null
        }
        val bitmap = Bitmap.createBitmap(container.width, container.height, Bitmap.Config.ARGB_8888)
        container.draw(Canvas(bitmap))
        bitmap
    }.getOrNull()

    private fun createPreviewAfterMissingStream(model: RealtimeModel): RealtimeMediaStream {
        disposeLocalStream()
        previewModel = model
        previewFacing = facing
        val stream = RealTimeClient.createLocalVideoStream(
            context = context,
            model = model,
            facing = facing,
            logger = AndroidLogger(LogLevel.WARN),
        )
        localStream = stream
        bindRenderer(RendererTarget.LOCAL, localContainer, stream, mirror = false)
        localContainer.visibility = View.VISIBLE
        return stream
    }

    private fun observe(newClient: RealTimeClient) {
        collectorJobs += newClient.connectionState
            .onEach(onConnectionState)
            .launchIn(scope)

        collectorJobs += newClient.errors
            .onEach { error ->
                Log.e(
                    TAG,
                    "Decart error code=${error.code} message=${error.message} data=${error.data}",
                    error.cause,
                )
                onError(error.message)
            }
            .launchIn(scope)

        collectorJobs += newClient.remoteStreamUpdates
            .onEach(::attachRemote)
            .launchIn(scope)

        collectorJobs += newClient.localStreamUpdates
            .onEach { stream ->
                localStream = stream
                bindRenderer(RendererTarget.LOCAL, localContainer, stream, mirror = false)
            }
            .launchIn(scope)
    }

    private fun attachRemote(stream: RealtimeMediaStream) {
        remoteStream = stream
        remoteContainer.visibility = View.VISIBLE
        bindRenderer(RendererTarget.REMOTE, remoteContainer, stream, mirror = false)
        onRemoteStream(true)
    }

    private fun bindRenderer(
        bindingTarget: RendererTarget,
        container: FrameLayout,
        stream: RealtimeMediaStream,
        mirror: Boolean,
    ) {
        val track = stream.videoTrack ?: return
        val room = stream.room ?: return
        val current = when (bindingTarget) {
            RendererTarget.LOCAL -> localBinding
            RendererTarget.REMOTE -> remoteBinding
        }

        if (current != null && current.track === track && current.room === room) {
            return
        }

        detachRenderer(current)

        val renderer = TextureViewRenderer(context).apply {
            init(
                room.lkObjects.eglBase.eglBaseContext,
                object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() = Unit

                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) = Unit
                },
            )
            setEnableHardwareScaler(true)
            setMirror(mirror)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        }

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.CENTER,
        )
        container.removeAllViews()
        container.addView(renderer, params)
        track.addRenderer(renderer)

        val binding = RendererBinding(container, renderer, track, room)
        when (bindingTarget) {
            RendererTarget.LOCAL -> localBinding = binding
            RendererTarget.REMOTE -> remoteBinding = binding
        }
    }

    private fun detachRenderer(binding: RendererBinding?) {
        if (binding == null) return
        runCatching { binding.track.removeRenderer(binding.renderer) }
        runCatching { binding.container.removeView(binding.renderer) }
        runCatching { binding.renderer.release() }
    }

    private fun disposeLocalStream() {
        detachRenderer(localBinding)
        localBinding = null
        localStream?.dispose()
        localStream = null
        previewFacing = null
    }

    private fun releaseClientOnly() {
        client?.let { current ->
            runCatching { current.disconnect() }
            runCatching { current.release() }
        }
        client = null
    }

    private fun stopCollectors() {
        collectorJobs.forEach { it.cancel() }
        collectorJobs.clear()
    }

    private data class RendererBinding(
        val container: FrameLayout,
        val renderer: TextureViewRenderer,
        val track: VideoTrack,
        val room: Room,
    )

    private enum class RendererTarget { LOCAL, REMOTE }

    companion object {
        private const val TAG = "KabudaRealtime"
        const val GARMENT_PROMPT = "Replace the current top with this garment."
    }
}
