package ai.anywear.mvp

import ai.anywear.mvp.data.BackendConfig
import ai.anywear.mvp.data.TryOnRepository
import ai.anywear.mvp.databinding.ActivityMainBinding
import ai.anywear.mvp.databinding.DialogFeedbackBinding
import ai.anywear.mvp.realtime.DecartRealtimeSession
import ai.anywear.mvp.recording.SessionArchive
import ai.anywear.mvp.recording.SessionMetadata
import ai.decart.sdk.ConnectionState
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.RealtimeModels
import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var repository: TryOnRepository
    private lateinit var realtimeSession: DecartRealtimeSession
    private val preferences: ai.anywear.mvp.data.AppPreferences
        get() = (application as KabudaApplication).preferences

    private var selectedImageUri: Uri? = null
    private var garmentFile: File? = null
    private var activeModel: RealtimeModel? = null
    private var cameraStarted = false
    private var tryonStarted = false
    private var tryonConnecting = false
    private var sessionId: String = UUID.randomUUID().toString()
    private var connectJob: Job? = null
    private var billingJob: Job? = null
    private var lastArchive: File? = null

    private val pickGarment = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onGarmentSelected(uri) }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraPreview()
        else toast(getString(R.string.camera_permission_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val app = application as KabudaApplication
        repository = TryOnRepository(this, app.preferences)
        sessionId = app.preferences.lastSessionId ?: sessionId
        realtimeSession = DecartRealtimeSession(
            context = this,
            localContainer = binding.localRenderer,
            remoteContainer = binding.remoteRenderer,
            onConnectionState = ::onConnectionState,
            onRemoteStream = ::onRemoteStream,
            onError = ::onRealtimeError,
        )

        binding.selectButton.setOnClickListener { pickGarment() }
        binding.cameraButton.setOnClickListener { toggleCamera() }
        binding.tryonButton.setOnClickListener { toggleTryOn() }
        binding.archiveButton.setOnClickListener { saveArchive() }
        binding.shareButton.setOnClickListener { lastArchive?.let { SessionArchive.share(this, it) } }
        binding.feedbackButton.setOnClickListener { showFeedbackDialog() }
        binding.billingButton.setOnClickListener { showBillingStatus() }
        binding.settingsButton.setOnClickListener { openSettings() }

        updateControls()
    }

    override fun onResume() {
        super.onResume()
        if (!tryonStarted && !tryonConnecting) {
            binding.statusText.text = getString(R.string.status_idle)
        }
    }

    override fun onDestroy() {
        billingJob?.cancel()
        connectJob?.cancel()
        realtimeSession.release()
        super.onDestroy()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    private fun pickGarment() {
        pickGarment.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun onGarmentSelected(uri: Uri) {
        selectedImageUri = uri
        garmentFile = repository.copyUriToCache(uri, "garment")
        binding.garmentPreview.setImageURI(uri)
        binding.garmentName.text = repository.displayName(uri) ?: getString(R.string.garment_selected)
        updateControls()
        lifecycleScope.launch { repository.log("garment_selected", sessionId) }
    }

    private fun toggleCamera() {
        if (cameraStarted) {
            stopTryOn(restartPreview = false)
            stopCameraPreview()
        } else {
            requestCamera()
        }
    }

    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCameraPreview()
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCameraPreview() {
        val model = RealtimeModels.fromName(preferences.modelName)
        if (model == null) {
            toast(getString(R.string.unsupported_model))
            return
        }
        runCatching { realtimeSession.startPreview(model) }
            .onSuccess {
                cameraStarted = true
                binding.cameraButton.setText(R.string.stop_camera)
                binding.remoteRenderer.visibility = View.GONE
                setLocalPreviewMode(pip = false)
                binding.statusText.text = getString(R.string.status_idle)
                updateControls()
            }
            .onFailure { error ->
                cameraStarted = false
                toast(getString(R.string.camera_start_failed, error.message ?: "unknown"))
            }
    }

    private fun stopCameraPreview() {
        realtimeSession.stopPreview()
        cameraStarted = false
        binding.cameraButton.setText(R.string.start_camera)
        binding.remoteRenderer.visibility = View.GONE
        binding.statusText.text = getString(R.string.status_idle)
        updateControls()
    }

    private fun toggleTryOn() {
        if (tryonConnecting || tryonStarted) {
            stopTryOn(restartPreview = cameraStarted)
            return
        }

        val garment = garmentFile
        if (garment == null) {
            toast(getString(R.string.garment_required))
            return
        }
        if (!cameraStarted) {
            toast(getString(R.string.camera_permission_required))
            return
        }
        if (preferences.apiKey.isBlank()) {
            toast(getString(R.string.decart_key_required))
            return
        }
        if (!BackendConfig.isRealtimeUrl(preferences.realtimeSignalingUrl)) {
            toast(getString(R.string.realtime_required))
            return
        }
        val model = RealtimeModels.fromName(preferences.modelName)
        if (model == null) {
            toast(getString(R.string.unsupported_model))
            return
        }

        startTryOn(garment, model)
    }

    private fun startTryOn(garment: File, model: RealtimeModel) {
        sessionId = UUID.randomUUID().toString()
        preferences.lastSessionId = sessionId
        tryonConnecting = true
        tryonStarted = false
        activeModel = null
        binding.tryonButton.setText(R.string.stop_tryon)
        binding.statusText.text = getString(R.string.tryon_connecting)
        updateControls()
        lifecycleScope.launch { repository.log("tryon_started", sessionId) }

        connectJob = lifecycleScope.launch {
            try {
                val imageDataUri = withContext(Dispatchers.IO) {
                    repository.garmentToDataUri(garment)
                }
                realtimeSession.connect(
                    apiKey = preferences.apiKey,
                    baseUrl = preferences.realtimeSignalingUrl,
                    model = model,
                    imageDataUri = imageDataUri,
                )

                activeModel = model
                tryonConnecting = false
                tryonStarted = true
                preferences.lastTryonId = realtimeSession.sessionId ?: sessionId
                binding.tryonButton.setText(R.string.stop_tryon)
                binding.statusText.text = getString(R.string.tryon_connected)
                updateControls()
                startBillingHeartbeat()
            } catch (error: CancellationException) {
                tryonConnecting = false
                updateControls()
                throw error
            } catch (error: Throwable) {
                failTryOn(error.message ?: getString(R.string.tryon_failed))
            }
        }
    }

    private fun stopTryOn(restartPreview: Boolean) {
        val wasActive = tryonConnecting || tryonStarted
        connectJob?.cancel()
        connectJob = null
        billingJob?.cancel()
        billingJob = null
        tryonConnecting = false
        tryonStarted = false
        realtimeSession.stopSession(activeModel, restartPreview = restartPreview && cameraStarted)
        activeModel = null
        binding.tryonButton.setText(R.string.start_tryon)
        binding.remoteRenderer.visibility = View.GONE
        setLocalPreviewMode(pip = false)
        if (cameraStarted) {
            binding.statusText.text = getString(R.string.status_idle)
        }
        updateControls()
        if (wasActive) {
            lifecycleScope.launch { repository.log("tryon_stopped", sessionId) }
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        val message = when (state) {
            ConnectionState.CONNECTING -> getString(R.string.tryon_connecting)
            ConnectionState.CONNECTED -> getString(R.string.tryon_connected)
            ConnectionState.GENERATING -> getString(R.string.tryon_generating)
            ConnectionState.RECONNECTING -> getString(R.string.tryon_reconnecting)
            ConnectionState.DISCONNECTED -> {
                if (tryonConnecting) return
                getString(R.string.tryon_disconnected)
            }
        }
        if (tryonConnecting || tryonStarted) {
            binding.statusText.text = message
        }
    }

    private fun onRemoteStream(available: Boolean) {
        binding.remoteRenderer.visibility = if (available) View.VISIBLE else View.GONE
        setLocalPreviewMode(pip = available)
    }

    private fun onRealtimeError(message: String?) {
        val text = message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tryon_error)
        if (tryonConnecting || tryonStarted) {
            failTryOn(text)
        } else {
            binding.statusText.text = text
        }
    }

    private fun failTryOn(message: String) {
        billingJob?.cancel()
        billingJob = null
        connectJob = null
        realtimeSession.stopSession(activeModel, restartPreview = cameraStarted)
        activeModel = null
        tryonConnecting = false
        tryonStarted = false
        binding.tryonButton.setText(R.string.start_tryon)
        binding.remoteRenderer.visibility = View.GONE
        setLocalPreviewMode(pip = false)
        binding.statusText.text = message
        updateControls()
        toast(message)
        lifecycleScope.launch { repository.log("tryon_failed", sessionId, mapOf("error" to message)) }
    }

    private fun setLocalPreviewMode(pip: Boolean) {
        val params = binding.localRenderer.layoutParams as ConstraintLayout.LayoutParams
        if (pip) {
            params.width = dp(112)
            params.height = dp(160)
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topMargin = dp(140)
            params.marginEnd = dp(16)
        } else {
            params.width = 0
            params.height = 0
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topMargin = 0
            params.marginEnd = 0
        }
        binding.localRenderer.layoutParams = params
        if (cameraStarted || tryonConnecting || tryonStarted) {
            binding.localRenderer.visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun startBillingHeartbeat() {
        billingJob?.cancel()
        if (BuildConfig.API_BASE_URL.isBlank()) return
        billingJob = lifecycleScope.launch {
            while (tryonStarted) {
                withContext(Dispatchers.IO) {
                    repository.billingHeartbeat(sessionId)
                }.onSuccess { status ->
                    preferences.lastBillingState =
                        if (status.insufficientBalance == true) "insufficient_balance" else "active"
                }.onFailure {
                    // Optional backend telemetry must never interrupt a live session.
                }
                delay(30_000)
            }
        }
    }

    private fun saveArchive() {
        val metadata = SessionMetadata(
            sessionId = sessionId,
            installationId = preferences.installationId,
            garmentFileName = garmentFile?.name,
            startedAt = System.currentTimeMillis() - 60_000,
            endedAt = System.currentTimeMillis(),
            state = when {
                tryonStarted -> "active"
                tryonConnecting -> "connecting"
                else -> "idle"
            }
        )
        val input = realtimeSession.capture(binding.localRenderer)
        val output = realtimeSession.capture(binding.remoteRenderer)
        val inputCache = SessionArchive.captureBitmapToCache(this, input, "archive-input")
        val outputCache = SessionArchive.captureBitmapToCache(this, output, "archive-output")
        val archive = SessionArchive.create(
            context = this,
            metadata = metadata,
            garmentFile = garmentFile,
            inputFrame = inputCache,
            outputFrame = outputCache,
            videoFile = null,
        )
        if (archive == null) {
            toast(getString(R.string.archive_failed))
            return
        }
        lastArchive = archive
        binding.shareButton.isEnabled = true
        toast(getString(R.string.archive_saved))
        lifecycleScope.launch { repository.log("session_archive_saved", sessionId) }
    }

    private fun showFeedbackDialog() {
        val dialogBinding = DialogFeedbackBinding.inflate(layoutInflater)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.feedback)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.upload_feedback) { _, _ ->
                uploadFeedback(dialogBinding.feedbackText.text?.toString())
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun uploadFeedback(description: String?) {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            toast(getString(R.string.backend_not_configured))
            return
        }
        val input = realtimeSession.capture(binding.localRenderer)
        val output = realtimeSession.capture(binding.remoteRenderer)
        if (input == null && output == null) {
            toast(getString(R.string.no_frames_to_upload))
            return
        }
        lifecycleScope.launch {
            val inputCache = SessionArchive.captureBitmapToCache(this@MainActivity, input, "feedback-input")
            val outputCache = SessionArchive.captureBitmapToCache(this@MainActivity, output, "feedback-output")
            val result = withContext(Dispatchers.IO) {
                repository.uploadFeedbackFrames(
                    sessionId = sessionId,
                    tryonId = preferences.lastTryonId ?: sessionId,
                    inputFrame = inputCache,
                    outputFrame = outputCache,
                )
            }
            result.onSuccess {
                toast(getString(R.string.feedback_uploaded))
                repository.log("feedback_uploaded", sessionId, mapOf("description" to description))
            }.onFailure { error ->
                toast(getString(R.string.feedback_upload_failed, error.message ?: "unknown"))
            }
        }
    }

    private fun showBillingStatus() {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            toast(getString(R.string.backend_not_configured))
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.billingStatus("android-mvp") }
            result.onSuccess { status ->
                val text = buildString {
                    appendLine(getString(R.string.billing_enabled, if (status.billingEnabled == true) "是" else "否"))
                    appendLine(getString(R.string.billing_remaining, status.tryonSessions ?: -1))
                    appendLine(getString(R.string.billing_free_pass, if (status.freePassAvailable == true) "可用" else "不可用"))
                    if (status.insufficientBalance == true) appendLine(getString(R.string.billing_insufficient))
                    if (status.capped == true) appendLine(getString(R.string.billing_capped))
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.billing)
                    .setMessage(text)
                    .setPositiveButton(R.string.confirm, null)
                    .show()
            }.onFailure { error ->
                toast(getString(R.string.billing_failed, error.message ?: "unknown"))
            }
        }
    }

    private fun updateControls() {
        binding.tryonButton.isEnabled = garmentFile != null && cameraStarted
        binding.cameraButton.isEnabled = !tryonConnecting && !tryonStarted
        binding.archiveButton.isEnabled = true
        binding.feedbackButton.isEnabled = !tryonConnecting
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}
