package ai.anywear.mvp.data

import ai.anywear.mvp.BuildConfig
import java.net.URI

/**
 * Build-time defaults only. Runtime values are editable in the in-app Settings
 * screen and are intentionally kept out of source-controlled secrets.
 */
object BackendConfig {
    val baseUrl: String = normalize(BuildConfig.API_BASE_URL)
    val realtimeSignalingUrl: String = BuildConfig.REALTIME_SIGNALING_URL

    fun normalize(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isBlank()) return trimmed
        return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
    }

    fun isApiUrl(url: String): Boolean =
        isUrlWithScheme(url, "http", "https")

    fun isRealtimeUrl(url: String): Boolean =
        isUrlWithScheme(url, "ws", "wss")

    private fun isUrlWithScheme(url: String, vararg schemes: String): Boolean {
        return runCatching {
            val uri = URI(url.trim())
            schemes.any { it.equals(uri.scheme, ignoreCase = true) } && !uri.host.isNullOrBlank()
        }.getOrDefault(false)
    }
}
