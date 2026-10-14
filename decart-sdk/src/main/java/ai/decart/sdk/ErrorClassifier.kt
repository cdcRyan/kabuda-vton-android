package ai.decart.sdk

object ErrorClassifier {
    fun classifyWebrtcError(error: Throwable, source: String? = null): DecartError {
        val msg = error.message?.lowercase() ?: ""

        if (source == "server") {
            return DecartError(ErrorCodes.WEBRTC_SERVER_ERROR, error.message ?: "Server error")
        }
        if (msg.contains("websocket")) {
            return DecartError(ErrorCodes.WEBRTC_WEBSOCKET_ERROR, "WebSocket connection failed", cause = error)
        }
        if (msg.contains("ice connection failed")) {
            return DecartError(ErrorCodes.WEBRTC_ICE_ERROR, "ICE connection failed", cause = error)
        }
        if (msg.contains("timeout") || msg.contains("timed out")) {
            val timeoutMatch = Regex("(\\d+)\\s*ms").find(msg)
            val timeoutMs = timeoutMatch?.groupValues?.get(1)?.toIntOrNull()
            return DecartError(
                ErrorCodes.WEBRTC_TIMEOUT_ERROR,
                if (timeoutMs != null) "connection timed out after ${timeoutMs}ms" else "connection timed out",
                data = mapOf("phase" to "connection", "timeoutMs" to timeoutMs),
                cause = error
            )
        }
        return DecartError(ErrorCodes.WEBRTC_SIGNALING_ERROR, "Signaling error", cause = error)
    }
}
