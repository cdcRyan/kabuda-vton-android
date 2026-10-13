package ai.anywear.mvp.data

import com.google.gson.annotations.SerializedName

data class TokenRequest(
    @SerializedName("installation_id") val installationId: String,
    val site: String,
    val model: String? = null
)

data class TokenResponse(
    val apiKey: String? = null,
    @SerializedName("expiresAt") val expiresAt: Long? = null,
    @SerializedName("issuedAt") val issuedAt: Long? = null,
    val model: String? = null,
    val endpoint: String? = null
)

data class QueueJoinRequest(
    @SerializedName("installation_id") val installationId: String,
    val site: String,
    @SerializedName("mint_receipt") val mintReceipt: String? = null
)

data class QueueJoinResponse(
    val ticket: String? = null,
    val status: String? = null,
    @SerializedName("position") val position: Int? = null
)

data class QueueStatusResponse(
    val status: String? = null,
    val position: Int? = null,
    @SerializedName("ready") val ready: Boolean? = null,
    @SerializedName("retry_after_ms") val retryAfterMs: Long? = null
)

data class BillingStatusResponse(
    @SerializedName("billing_enabled") val billingEnabled: Boolean? = null,
    @SerializedName("tryon_sessions") val tryonSessions: Int? = null,
    @SerializedName("free_pass_available") val freePassAvailable: Boolean? = null,
    @SerializedName("insufficient_balance") val insufficientBalance: Boolean? = null,
    @SerializedName("capped") val capped: Boolean? = null
)

data class BillingHeartbeatRequest(
    @SerializedName("installation_id") val installationId: String,
    @SerializedName("session_id") val sessionId: String
)

data class BillingHeartbeatResponse(
    @SerializedName("billed_seconds") val billedSeconds: Int? = null,
    @SerializedName("capped") val capped: Boolean? = null,
    @SerializedName("insufficient_balance") val insufficientBalance: Boolean? = null,
    @SerializedName("unbilled") val unbilled: Boolean? = null
)

data class LogEvent(
    val event: String,
    @SerializedName("occurred_at") val occurredAt: String,
    @SerializedName("installation_id") val installationId: String,
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("page_url") val pageUrl: String? = null,
    val properties: Map<String, Any?> = emptyMap()
)
