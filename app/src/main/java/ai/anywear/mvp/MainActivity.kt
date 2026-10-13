package ai.anywear.mvp

import ai.anywear.mvp.data.BackendConfig
import ai.anywear.mvp.data.TryOnRepository
import ai.anywear.mvp.databinding.ActivityMainBinding
import ai.anywear.mvp.databinding.DialogFeedbackBinding
import ai.anywear.mvp.databinding.ItemActionBinding
import ai.anywear.mvp.databinding.ItemStepBinding
import ai.anywear.mvp.databinding.SheetActionsBinding
import ai.anywear.mvp.databinding.SheetBillingBinding
import ai.anywear.mvp.realtime.DecartRealtimeSession
import ai.anywear.mvp.recording.SessionArchive
import ai.anywear.mvp.recording.SessionMetadata
import ai.decart.sdk.ConnectionState
import ai.decart.sdk.RealtimeModel
import ai.decart.sdk.RealtimeModels
import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.PathInterpolator
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorRes
import androidx.annotation.DrawableRes
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
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

    // ── Presentation state ──────────────────────────────────────────────
    private enum class Tone { IDLE, READY, BUSY, LIVE, ERROR }

    private var liveState: ConnectionState? = null
    private var errorMessage: String? = null
    private var pulse: ObjectAnimator? = null
    private var shutterLive: Boolean? = null
    private val shutterCore = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE }
    private val emphasized = PathInterpolator(0.2f, 0f, 0f, 1f)

    private val pickGarment = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) onGarmentSelected(uri) }

    private val requestCameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCameraPreview()
        else notify(getString(R.string.camera_permission_required))
    }

    private val settingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) notify(getString(R.string.settings_saved))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
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

        applyWindowInsets()
        setupPipOutline()
        binding.tryonCore.background = shutterCore

        binding.selectButton.setOnClickListener { pickGarment() }
        binding.cameraButton.setOnClickListener { toggleCamera() }
        binding.tryonButton.setOnClickListener { view ->
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            toggleTryOn()
        }
        binding.moreButton.setOnClickListener { showSessionSheet() }
        binding.settingsButton.setOnClickListener { openSettings() }

        binding.stepKey.stepRow.setOnClickListener { openSettings() }
        binding.stepGarment.stepRow.setOnClickListener { pickGarment() }
        binding.stepCamera.stepRow.setOnClickListener { requestCamera() }

        render()
    }

    override fun onResume() {
        super.onResume()
        if (!tryonStarted && !tryonConnecting) errorMessage = null
        render()
    }

    override fun onDestroy() {
        pulse?.cancel()
        billingJob?.cancel()
        connectJob?.cancel()
        realtimeSession.release()
        super.onDestroy()
    }

    private fun openSettings() {
        settingsLauncher.launch(Intent(this, SettingsActivity::class.java))
    }

    private fun pickGarment() {
        pickGarment.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun onGarmentSelected(uri: Uri) {
        selectedImageUri = uri
        garmentFile = repository.copyUriToCache(uri, "garment")
        binding.garmentPreview.apply {
            imageTintList = null
            setPadding(0, 0, 0, 0)
            scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
            setImageURI(uri)
            alpha = 0f
            animate().alpha(1f).setDuration(220).start()
        }
        binding.garmentName.text = repository.displayName(uri) ?: getString(R.string.garment_selected)
        errorMessage = null
        render()
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
            notify(getString(R.string.unsupported_model))
            return
        }
        runCatching { realtimeSession.startPreview(model) }
            .onSuccess {
                cameraStarted = true
                errorMessage = null
                binding.remoteRenderer.visibility = View.GONE
                setLocalPreviewMode(pip = false)
                binding.localRenderer.alpha = 0f
                binding.localRenderer.animate().alpha(1f).setDuration(280).start()
                render()
            }
            .onFailure { error ->
                cameraStarted = false
                notify(getString(R.string.camera_start_failed, error.message ?: "unknown"))
                render()
            }
    }

    private fun stopCameraPreview() {
        realtimeSession.stopPreview()
        cameraStarted = false
        binding.remoteRenderer.visibility = View.GONE
        render()
    }

    private fun toggleTryOn() {
        if (tryonConnecting || tryonStarted) {
            stopTryOn(restartPreview = cameraStarted)
            return
        }

        val garment = garmentFile
        if (garment == null) {
            nudge(getString(R.string.garment_required))
            return
        }
        if (!cameraStarted) {
            nudge(getString(R.string.status_need_camera))
            return
        }
        if (preferences.apiKey.isBlank()) {
            nudge(getString(R.string.decart_key_required))
            return
        }
        if (!BackendConfig.isRealtimeUrl(preferences.realtimeSignalingUrl)) {
            nudge(getString(R.string.realtime_required))
            return
        }
        val model = RealtimeModels.fromName(preferences.modelName)
        if (model == null) {
            nudge(getString(R.string.unsupported_model))
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
        errorMessage = null
        liveState = ConnectionState.CONNECTING
        render()
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
                if (liveState == ConnectionState.CONNECTING) liveState = ConnectionState.CONNECTED
                render()
                startBillingHeartbeat()
            } catch (error: CancellationException) {
                tryonConnecting = false
                render()
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
        liveState = null
        realtimeSession.stopSession(activeModel, restartPreview = restartPreview && cameraStarted)
        activeModel = null
        binding.remoteRenderer.visibility = View.GONE
        setLocalPreviewMode(pip = false)
        render()
        if (wasActive) {
            lifecycleScope.launch { repository.log("tryon_stopped", sessionId) }
        }
    }

    private fun onConnectionState(state: ConnectionState) {
        if (state == ConnectionState.DISCONNECTED && tryonConnecting) return
        if (tryonConnecting || tryonStarted) {
            liveState = state
            render()
        }
    }

    private fun onRemoteStream(available: Boolean) {
        binding.remoteRenderer.visibility = if (available) View.VISIBLE else View.GONE
        setLocalPreviewMode(pip = available)
        if (available) {
            binding.remoteRenderer.alpha = 0f
            binding.remoteRenderer.animate().alpha(1f).setDuration(360).start()
        }
    }

    private fun onRealtimeError(message: String?) {
        val text = message?.takeIf { it.isNotBlank() }
            ?: getString(R.string.tryon_error)
        if (tryonConnecting || tryonStarted) {
            failTryOn(text)
        } else {
            errorMessage = text
            render()
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
        liveState = null
        binding.remoteRenderer.visibility = View.GONE
        setLocalPreviewMode(pip = false)
        errorMessage = message
        render()
        notify(message)
        lifecycleScope.launch { repository.log("tryon_failed", sessionId, mapOf("error" to message)) }
    }

    // ── Picture-in-picture ──────────────────────────────────────────────

    private fun setupPipOutline() {
        binding.localRenderer.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(20).toFloat())
            }
        }
    }

    private fun setLocalPreviewMode(pip: Boolean) {
        val params = binding.localRenderer.layoutParams as ConstraintLayout.LayoutParams
        if (pip) {
            params.width = dp(104)
            params.height = dp(148)
            params.topToTop = ConstraintLayout.LayoutParams.UNSET
            params.topToBottom = R.id.topBar
            params.bottomToBottom = ConstraintLayout.LayoutParams.UNSET
            params.startToStart = ConstraintLayout.LayoutParams.UNSET
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topMargin = dp(14)
            params.marginEnd = dp(16)
        } else {
            params.width = 0
            params.height = 0
            params.topToBottom = ConstraintLayout.LayoutParams.UNSET
            params.topToTop = ConstraintLayout.LayoutParams.PARENT_ID
            params.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            params.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
            params.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
            params.topMargin = 0
            params.marginEnd = 0
        }
        binding.localRenderer.layoutParams = params
        binding.localRenderer.clipToOutline = pip
        binding.localRenderer.foreground =
            if (pip) ContextCompat.getDrawable(this, R.drawable.bg_pip_border) else null
        binding.localRenderer.elevation = if (pip) dp(6).toFloat() else 0f
        if (pip) {
            binding.localRenderer.scaleX = 0.85f
            binding.localRenderer.scaleY = 0.85f
            binding.localRenderer.animate().scaleX(1f).scaleY(1f)
                .setInterpolator(emphasized).setDuration(320).start()
        }
        if (cameraStarted || tryonConnecting || tryonStarted) {
            binding.localRenderer.visibility = View.VISIBLE
        }
    }

    // ── Rendering ───────────────────────────────────────────────────────

    private fun render() {
        renderStatus()
        renderDock()
        renderEmptyState()
        binding.scrimTop.isVisible = cameraStarted
        binding.scrimBottom.isVisible = cameraStarted
    }

    private fun renderStatus() {
        val (tone, text) = when {
            tryonConnecting || tryonStarted -> when (liveState) {
                ConnectionState.GENERATING -> Tone.LIVE to getString(R.string.tryon_generating)
                ConnectionState.CONNECTED -> Tone.LIVE to getString(R.string.tryon_connected)
                ConnectionState.RECONNECTING -> Tone.BUSY to getString(R.string.tryon_reconnecting)
                ConnectionState.DISCONNECTED -> Tone.ERROR to getString(R.string.tryon_disconnected)
                else -> Tone.BUSY to getString(R.string.tryon_connecting)
            }
            errorMessage != null -> Tone.ERROR to errorMessage!!
            preferences.apiKey.isBlank() -> Tone.IDLE to getString(R.string.status_need_key)
            garmentFile == null -> Tone.IDLE to getString(R.string.status_need_garment)
            !cameraStarted -> Tone.IDLE to getString(R.string.status_need_camera)
            else -> Tone.READY to getString(R.string.status_ready)
        }

        binding.statusText.text = text
        binding.liveBadge.isVisible = tone == Tone.LIVE
        val dotColor = when (tone) {
            Tone.IDLE -> R.color.kb_ink_faint
            Tone.READY -> R.color.kb_mint
            Tone.BUSY -> R.color.kb_amber
            Tone.LIVE, Tone.ERROR -> R.color.kb_live
        }
        binding.statusDot.backgroundTintList = ColorStateList.valueOf(color(dotColor))

        val shouldPulse = tone == Tone.BUSY || tone == Tone.LIVE
        if (shouldPulse && pulse == null) {
            pulse = ObjectAnimator.ofFloat(binding.statusDot, View.ALPHA, 1f, 0.25f).apply {
                duration = if (tone == Tone.LIVE) 900 else 520
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else if (!shouldPulse) {
            pulse?.cancel()
            pulse = null
            binding.statusDot.alpha = 1f
        }
    }

    private fun renderDock() {
        val active = tryonConnecting || tryonStarted
        val ready = garmentFile != null && cameraStarted

        // Garment
        binding.garmentBadge.isVisible = garmentFile == null
        if (garmentFile == null) binding.garmentName.setText(R.string.dock_garment)
        binding.selectButton.isEnabled = !active
        binding.selectButton.alpha = if (active) 0.4f else 1f

        // Camera
        binding.cameraIcon.setImageResource(if (cameraStarted) R.drawable.ic_camera_off else R.drawable.ic_camera)
        binding.cameraLabel.setText(if (cameraStarted) R.string.dock_camera_off else R.string.dock_camera_on)
        binding.cameraButton.contentDescription = binding.cameraLabel.text
        binding.cameraButton.isEnabled = !active
        binding.cameraButton.alpha = if (active) 0.4f else 1f

        // Shutter
        binding.tryonProgress.isVisible = tryonConnecting ||
            (tryonStarted && liveState == ConnectionState.RECONNECTING)
        binding.tryonLabel.setText(if (active) R.string.stop_tryon else R.string.start_tryon)
        binding.tryonButton.contentDescription = binding.tryonLabel.text
        binding.tryonButton.animate().alpha(if (ready || active) 1f else 0.45f).setDuration(160).start()
        morphShutter(live = active)
    }

    private fun morphShutter(live: Boolean) {
        if (shutterLive == live) return
        val first = shutterLive == null
        shutterLive = live

        val fromColor = color(if (live) R.color.kb_ink else R.color.kb_live)
        val toColor = color(if (live) R.color.kb_live else R.color.kb_ink)
        // Core is 64dp; at 0.5 scale a 14dp radius reads as a soft 7dp "stop" square.
        val fromRadius = dp(if (live) 32 else 14).toFloat()
        val toRadius = dp(if (live) 14 else 32).toFloat()
        val toScale = if (live) 0.5f else 1f

        binding.tryonIcon.animate().alpha(if (live) 0f else 1f).setDuration(140).start()

        if (first) {
            shutterCore.setColor(toColor)
            shutterCore.cornerRadius = toRadius
            binding.tryonCore.scaleX = toScale
            binding.tryonCore.scaleY = toScale
            return
        }
        val argb = ArgbEvaluator()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 300
            interpolator = emphasized
            addUpdateListener {
                val t = it.animatedFraction
                shutterCore.setColor(argb.evaluate(t, fromColor, toColor) as Int)
                shutterCore.cornerRadius = fromRadius + (toRadius - fromRadius) * t
            }
            start()
        }
        binding.tryonCore.animate().scaleX(toScale).scaleY(toScale)
            .setInterpolator(emphasized).setDuration(300).start()
    }

    private fun renderEmptyState() {
        val show = !cameraStarted
        if (show != binding.emptyState.isVisible) {
            if (show) {
                binding.emptyState.alpha = 0f
                binding.emptyState.translationY = dp(12).toFloat()
                binding.emptyState.isVisible = true
                binding.emptyState.animate().alpha(1f).translationY(0f)
                    .setInterpolator(emphasized).setDuration(320).start()
            } else {
                binding.emptyState.isVisible = false
            }
        }
        if (!show) return

        val hasKey = preferences.apiKey.isNotBlank()
        bindStep(
            binding.stepKey, 1, done = hasKey,
            title = getString(R.string.step_key_title),
            caption = if (hasKey) getString(R.string.step_key_done, preferences.modelName)
            else getString(R.string.step_key_todo),
        )
        bindStep(
            binding.stepGarment, 2, done = garmentFile != null,
            title = getString(R.string.step_garment_title),
            caption = if (garmentFile != null) binding.garmentName.text.toString()
            else getString(R.string.step_garment_todo),
        )
        bindStep(
            binding.stepCamera, 3, done = false,
            title = getString(R.string.step_camera_title),
            caption = getString(R.string.step_camera_todo),
        )
    }

    private fun bindStep(step: ItemStepBinding, index: Int, done: Boolean, title: String, caption: String) {
        step.stepIndex.text = index.toString()
        step.stepIndex.isVisible = !done
        step.stepCheck.isVisible = done
        step.stepTitle.text = title
        step.stepTitle.setTextColor(color(if (done) R.color.kb_ink_muted else R.color.kb_ink))
        step.stepCaption.text = caption
        step.stepChevron.isVisible = !done
    }

    // ── Billing heartbeat (unchanged behaviour) ─────────────────────────

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

    // ── Session sheet ───────────────────────────────────────────────────

    private fun showSessionSheet() {
        val dialog = BottomSheetDialog(this)
        val sheet = SheetActionsBinding.inflate(layoutInflater)
        val hasBackend = BuildConfig.API_BASE_URL.isNotBlank()

        bindAction(
            sheet.archiveButton, R.drawable.ic_download,
            getString(R.string.save_session), getString(R.string.action_save_caption),
        ) { dialog.dismiss(); saveArchive() }

        bindAction(
            sheet.shareButton, R.drawable.ic_share,
            getString(R.string.share_session),
            getString(if (lastArchive != null) R.string.action_share_caption else R.string.action_share_caption_empty),
            enabled = lastArchive != null,
        ) { dialog.dismiss(); lastArchive?.let { SessionArchive.share(this, it) } }

        bindAction(
            sheet.feedbackButton, R.drawable.ic_feedback,
            getString(R.string.feedback),
            getString(if (hasBackend) R.string.action_feedback_caption else R.string.action_needs_backend),
            enabled = !tryonConnecting,
        ) { dialog.dismiss(); showFeedbackDialog() }

        bindAction(
            sheet.billingButton, R.drawable.ic_wallet,
            getString(R.string.billing),
            getString(if (hasBackend) R.string.action_billing_caption else R.string.action_needs_backend),
        ) { dialog.dismiss(); showBillingStatus() }

        dialog.setContentView(sheet.root)
        dialog.show()
    }

    private fun bindAction(
        row: ItemActionBinding,
        @DrawableRes icon: Int,
        title: String,
        caption: String,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        row.actionIcon.setImageResource(icon)
        row.actionTitle.text = title
        row.actionCaption.text = caption
        row.actionRow.isEnabled = enabled
        row.actionRow.alpha = if (enabled) 1f else 0.4f
        row.actionRow.setOnClickListener { onClick() }
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
            notify(getString(R.string.archive_failed))
            return
        }
        lastArchive = archive
        notify(getString(R.string.archive_saved), actionLabel = getString(R.string.share_session)) {
            SessionArchive.share(this, archive)
        }
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
            notify(getString(R.string.backend_not_configured))
            return
        }
        val input = realtimeSession.capture(binding.localRenderer)
        val output = realtimeSession.capture(binding.remoteRenderer)
        if (input == null && output == null) {
            notify(getString(R.string.no_frames_to_upload))
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
                notify(getString(R.string.feedback_uploaded))
                repository.log("feedback_uploaded", sessionId, mapOf("description" to description))
            }.onFailure { error ->
                notify(getString(R.string.feedback_upload_failed, error.message ?: "unknown"))
            }
        }
    }

    private fun showBillingStatus() {
        if (BuildConfig.API_BASE_URL.isBlank()) {
            notify(getString(R.string.backend_not_configured))
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.billingStatus("android-mvp") }
            result.onSuccess { status ->
                val sheet = SheetBillingBinding.inflate(layoutInflater)
                sheet.billingRemaining.text = status.tryonSessions?.toString() ?: "—"
                sheet.billingEnabled.setText(if (status.billingEnabled == true) R.string.yes else R.string.no)
                sheet.billingFreePass.setText(
                    if (status.freePassAvailable == true) R.string.available else R.string.unavailable
                )
                val warning = when {
                    status.insufficientBalance == true -> getString(R.string.billing_insufficient)
                    status.capped == true -> getString(R.string.billing_capped)
                    else -> null
                }
                sheet.billingWarning.isVisible = warning != null
                sheet.billingWarning.text = warning
                BottomSheetDialog(this@MainActivity).apply {
                    setContentView(sheet.root)
                    show()
                }
            }.onFailure { error ->
                notify(getString(R.string.billing_failed, error.message ?: "unknown"))
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private fun applyWindowInsets() {
        val topPad = binding.topBar.paddingTop
        val bottomPad = binding.dock.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.topBar.updatePadding(top = topPad + bars.top, left = bars.left, right = bars.right)
            binding.dock.updatePadding(bottom = bottomPad + bars.bottom)
            insets
        }
    }

    /** Snackbar anchored above the dock, so it never covers the controls. */
    private fun notify(message: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        val bar = Snackbar.make(binding.root, message, Snackbar.LENGTH_LONG)
            .setAnchorView(binding.dock)
        if (actionLabel != null && action != null) {
            bar.setAction(actionLabel) { action() }
            bar.setActionTextColor(color(R.color.kb_live))
        }
        bar.show()
    }

    /** Gentle horizontal shake on the shutter plus a hint, for "not ready yet" taps. */
    private fun nudge(message: String) {
        ObjectAnimator.ofFloat(
            binding.tryonButton, View.TRANSLATION_X,
            0f, -dp(8).toFloat(), dp(8).toFloat(), -dp(5).toFloat(), dp(5).toFloat(), 0f,
        ).setDuration(360).start()
        notify(message)
    }

    private fun color(@ColorRes id: Int): Int = ContextCompat.getColor(this, id)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
