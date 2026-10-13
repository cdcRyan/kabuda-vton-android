package ai.anywear.mvp

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import ai.anywear.mvp.data.BackendConfig
import ai.anywear.mvp.databinding.ActivitySettingsBinding
import ai.decart.sdk.RealtimeModels

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val preferences: ai.anywear.mvp.data.AppPreferences
        get() = (application as KabudaApplication).preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.saveButton.setOnClickListener { save() }
        binding.resetButton.setOnClickListener { reset() }
        binding.apiKeyInput.setEndIconOnClickListener {
            val input = binding.apiKeyInput.editText ?: return@setEndIconOnClickListener
            val passwordMask = android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            val visible = (input.inputType and passwordMask) == 0
            input.inputType = if (visible) {
                android.text.InputType.TYPE_CLASS_TEXT or passwordMask
            } else {
                android.text.InputType.TYPE_CLASS_TEXT
            }
            input.setSelection(input.text?.length ?: 0)
            binding.apiKeyInput.endIconDrawable =
                getDrawable(if (visible) R.drawable.ic_visibility_off else R.drawable.ic_visibility)
        }

        binding.apiUrlInput.editText?.setText(preferences.apiBaseUrl)
        binding.realtimeUrlInput.editText?.setText(preferences.realtimeSignalingUrl)
        binding.apiKeyInput.editText?.setText(preferences.apiKey)
        binding.modelInput.editText?.setText(preferences.modelName)
        binding.telemetrySwitch.isChecked = preferences.telemetryEnabled
    }

    private fun save() {
        val baseUrl = binding.apiUrlInput.editText?.text?.toString()?.trim().orEmpty()
        val realtimeUrl = binding.realtimeUrlInput.editText?.text?.toString()?.trim().orEmpty()
        val apiKey = binding.apiKeyInput.editText?.text?.toString()?.trim().orEmpty()
        val model = binding.modelInput.editText?.text?.toString()?.trim().orEmpty()

        if (baseUrl.isNotBlank() && !BackendConfig.isApiUrl(baseUrl)) {
            binding.apiUrlInput.error = getString(R.string.settings_api_url_invalid)
            return
        }
        if (realtimeUrl.isBlank()) {
            binding.realtimeUrlInput.error = getString(R.string.settings_realtime_url_required)
            return
        }
        if (!BackendConfig.isRealtimeUrl(realtimeUrl)) {
            binding.realtimeUrlInput.error = getString(R.string.settings_realtime_url_invalid)
            return
        }
        if (apiKey.isBlank()) {
            binding.apiKeyInput.error = getString(R.string.settings_api_key_required)
            return
        }
        if (model.isBlank()) {
            binding.modelInput.error = getString(R.string.settings_model_required)
            return
        }
        if (RealtimeModels.fromName(model) == null) {
            binding.modelInput.error = getString(R.string.settings_model_invalid)
            return
        }

        preferences.apiBaseUrl = BackendConfig.normalize(baseUrl)
        preferences.realtimeSignalingUrl = realtimeUrl
        preferences.apiKey = apiKey
        preferences.modelName = model
        preferences.telemetryEnabled = binding.telemetrySwitch.isChecked

        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun reset() {
        preferences.resetConnectionDefaults()
        preferences.telemetryEnabled = true
        binding.apiUrlInput.editText?.setText(BuildConfig.API_BASE_URL)
        binding.realtimeUrlInput.editText?.setText(BuildConfig.REALTIME_SIGNALING_URL)
        binding.apiKeyInput.editText?.setText("")
        binding.modelInput.editText?.setText(BuildConfig.DEFAULT_MODEL_NAME)
        binding.telemetrySwitch.isChecked = true
        clearErrors()
        Toast.makeText(this, R.string.settings_reset_done, Toast.LENGTH_SHORT).show()
    }

    private fun clearErrors() {
        binding.apiUrlInput.error = null
        binding.realtimeUrlInput.error = null
        binding.apiKeyInput.error = null
        binding.modelInput.error = null
    }
}
