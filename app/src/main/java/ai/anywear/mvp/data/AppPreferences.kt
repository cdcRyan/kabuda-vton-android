package ai.anywear.mvp.data

import android.content.Context
import ai.anywear.mvp.BuildConfig
import java.util.UUID

class AppPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("kabuda_mvp", Context.MODE_PRIVATE)

    val installationId: String
        get() = prefs.getString("installation_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("installation_id", it).apply()
        }

    var apiBaseUrl: String
        get() = prefs.getString("api_base_url", BuildConfig.API_BASE_URL) ?: BuildConfig.API_BASE_URL
        set(value) = prefs.edit().putString("api_base_url", value.trim()).apply()

    var realtimeSignalingUrl: String
        get() = prefs.getString("realtime_signaling_url", BuildConfig.REALTIME_SIGNALING_URL) ?: BuildConfig.REALTIME_SIGNALING_URL
        set(value) = prefs.edit().putString("realtime_signaling_url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var modelName: String
        get() = prefs.getString("model_name", BuildConfig.DEFAULT_MODEL_NAME) ?: BuildConfig.DEFAULT_MODEL_NAME
        set(value) = prefs.edit().putString("model_name", value.trim()).apply()

    var lastSessionId: String?
        get() = prefs.getString("last_session_id", null)
        set(value) = prefs.edit().putString("last_session_id", value).apply()

    var lastTryonId: String?
        get() = prefs.getString("last_tryon_id", null)
        set(value) = prefs.edit().putString("last_tryon_id", value).apply()

    var lastBillingState: String?
        get() = prefs.getString("last_billing_state", null)
        set(value) = prefs.edit().putString("last_billing_state", value).apply()

    var telemetryEnabled: Boolean
        get() = prefs.getBoolean("telemetry_enabled", true)
        set(value) = prefs.edit().putBoolean("telemetry_enabled", value).apply()

    fun resetConnectionDefaults() {
        prefs.edit()
            .remove("api_base_url")
            .remove("realtime_signaling_url")
            .remove("api_key")
            .remove("model_name")
            .apply()
    }
}
