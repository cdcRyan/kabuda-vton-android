package ai.anywear.mvp

import ai.anywear.mvp.data.BackendConfig
import ai.anywear.mvp.databinding.ActivitySettingsBinding
import ai.decart.sdk.RealtimeModels
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val preferences: ai.anywear.mvp.data.AppPreferences
        get() = (application as KabudaApplication).preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyWindowInsets()

        binding.backButton.setOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.resetButton.setOnClickListener { confirmReset() }
        binding.telemetryRow.setOnClickListener { binding.telemetrySwitch.toggle() }

        val models = resources.getStringArray(R.array.realtime_models)
        binding.modelField.setAdapter(ArrayAdapter(this, R.layout.item_dropdown, models))

        binding.apiUrlInput.editText?.setText(preferences.apiBaseUrl)
        binding.realtimeUrlInput.editText?.setText(preferences.realtimeSignalingUrl)
        binding.apiKeyInput.editText?.setText(preferences.apiKey)
        binding.modelField.setText(preferences.modelName, false)
        binding.telemetrySwitch.isChecked = preferences.telemetryEnabled

        // Clear a field's error as soon as the user starts fixing it.
        listOf(
            binding.apiUrlInput, binding.realtimeUrlInput,
            binding.apiKeyInput, binding.modelInput,
        ).forEach { layout ->
            layout.editText?.doAfterTextChanged { layout.error = null }
        }
    }

    private fun save() {
        clearErrors()
        val baseUrl = binding.apiUrlInput.editText?.text?.toString()?.trim().orEmpty()
        val realtimeUrl = binding.realtimeUrlInput.editText?.text?.toString()?.trim().orEmpty()
        val apiKey = binding.apiKeyInput.editText?.text?.toString()?.trim().orEmpty()
        val model = binding.modelField.text?.toString()?.trim().orEmpty()

        if (apiKey.isBlank()) {
            return fail(binding.apiKeyInput, R.string.settings_api_key_required)
        }
        if (model.isBlank()) {
            return fail(binding.modelInput, R.string.settings_model_required)
        }
        if (RealtimeModels.fromName(model) == null) {
            return fail(binding.modelInput, R.string.settings_model_invalid)
        }
        if (realtimeUrl.isBlank()) {
            return fail(binding.realtimeUrlInput, R.string.settings_realtime_url_required)
        }
        if (!BackendConfig.isRealtimeUrl(realtimeUrl)) {
            return fail(binding.realtimeUrlInput, R.string.settings_realtime_url_invalid)
        }
        if (baseUrl.isNotBlank() && !BackendConfig.isApiUrl(baseUrl)) {
            return fail(binding.apiUrlInput, R.string.settings_api_url_invalid)
        }

        preferences.apiBaseUrl = BackendConfig.normalize(baseUrl)
        preferences.realtimeSignalingUrl = realtimeUrl
        preferences.apiKey = apiKey
        preferences.modelName = model
        preferences.telemetryEnabled = binding.telemetrySwitch.isChecked

        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun fail(layout: TextInputLayout, message: Int) {
        layout.error = getString(message)
        layout.requestFocus()
        binding.scroll.post {
            binding.scroll.smoothScrollTo(0, (layout.parent as android.view.View).top)
        }
    }

    private fun confirmReset() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setPositiveButton(R.string.settings_reset_confirm) { _, _ -> reset() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun reset() {
        preferences.resetConnectionDefaults()
        preferences.telemetryEnabled = true
        binding.apiUrlInput.editText?.setText(BuildConfig.API_BASE_URL)
        binding.realtimeUrlInput.editText?.setText(BuildConfig.REALTIME_SIGNALING_URL)
        binding.apiKeyInput.editText?.setText("")
        binding.modelField.setText(BuildConfig.DEFAULT_MODEL_NAME, false)
        binding.telemetrySwitch.isChecked = true
        clearErrors()
        Snackbar.make(binding.root, R.string.settings_reset_done, Snackbar.LENGTH_SHORT)
            .setAnchorView(binding.saveBar)
            .show()
    }

    private fun clearErrors() {
        binding.apiUrlInput.error = null
        binding.realtimeUrlInput.error = null
        binding.apiKeyInput.error = null
        binding.modelInput.error = null
    }

    private fun applyWindowInsets() {
        val contentTop = binding.content.paddingTop
        val barBottom = binding.saveBar.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            binding.content.updatePadding(top = contentTop + bars.top)
            binding.saveBar.updatePadding(bottom = barBottom + maxOf(bars.bottom, ime.bottom))
            insets
        }
    }
}
