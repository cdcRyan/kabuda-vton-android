package ai.anywear.mvp.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class TryOnRepository(
    private val context: Context,
    private val preferences: AppPreferences
) {
    private val api: TryOnApi
        get() = if (preferences.apiBaseUrl.isBlank()) {
            error("后端 API 地址未配置")
        } else {
            TryOnApiClient.apiFor(preferences.apiBaseUrl) { preferences.apiKey }
        }

    suspend fun getToken(site: String): Result<TokenResponse> = runCatching {
        val response = api.getToken(TokenRequest(preferences.installationId, site, preferences.modelName))
        if (response.isSuccessful && response.body() != null) response.body()!!
        else error("Token request failed: HTTP ${response.code()}")
    }

    suspend fun joinQueue(site: String): Result<QueueJoinResponse> = runCatching {
        val response = api.joinQueue(QueueJoinRequest(preferences.installationId, site))
        if (response.isSuccessful && response.body() != null) response.body()!!
        else error("Queue join failed: HTTP ${response.code()}")
    }

    suspend fun queueStatus(ticket: String): Result<QueueStatusResponse> = runCatching {
        val response = api.queueStatus(ticket)
        if (response.isSuccessful && response.body() != null) response.body()!!
        else error("Queue status failed: HTTP ${response.code()}")
    }

    suspend fun queueHeartbeat(site: String): Result<Unit> = runCatching {
        api.queueHeartbeat(QueueJoinRequest(preferences.installationId, site))
        Unit
    }

    suspend fun leaveQueue(site: String): Result<Unit> = runCatching {
        api.leaveQueue(QueueJoinRequest(preferences.installationId, site))
        Unit
    }

    suspend fun billingStatus(site: String): Result<BillingStatusResponse> = runCatching {
        val response = api.billingStatus(QueueJoinRequest(preferences.installationId, site))
        if (response.isSuccessful && response.body() != null) response.body()!!
        else error("Billing status failed: HTTP ${response.code()}")
    }

    suspend fun billingHeartbeat(sessionId: String): Result<BillingHeartbeatResponse> = runCatching {
        val response = api.billingHeartbeat(
            BillingHeartbeatRequest(preferences.installationId, sessionId)
        )
        if (response.isSuccessful && response.body() != null) response.body()!!
        else error("Billing heartbeat failed: HTTP ${response.code()}")
    }

    suspend fun log(event: String, sessionId: String? = null, properties: Map<String, Any?> = emptyMap()): Result<Unit> =
        runCatching {
            if (!preferences.telemetryEnabled) return@runCatching
            api.log(
                LogEvent(
                    event = event,
                    occurredAt = isoNow(),
                    installationId = preferences.installationId,
                    sessionId = sessionId,
                    pageUrl = null,
                    properties = properties
                )
            )
            Unit
        }

    suspend fun uploadFeedbackFrames(
        sessionId: String,
        tryonId: String,
        inputFrame: File?,
        outputFrame: File?
    ): Result<Unit> = runCatching {
        val parts = mutableListOf<MultipartBody.Part>()
        if (inputFrame != null) {
            parts += part("label", "input-frame")
            parts += filePart("frame", inputFrame, "image/jpeg")
        }
        if (outputFrame != null) {
            parts += part("label", "output-frame")
            parts += filePart("frame", outputFrame, "image/jpeg")
        }
        if (parts.isEmpty()) return@runCatching
        // The MVP endpoint accepts one frame per request; upload sequentially.
        var index = 0
        while (index < parts.size) {
            val label = parts[index]
            val frame = parts.getOrNull(index + 1) ?: break
            api.uploadFeedbackFrame(
                label = label,
                frame = frame,
                sessionId = part("session_id", sessionId),
                tryonId = part("tryon_id", tryonId)
            )
            index += 2
        }
    }

    fun copyUriToCache(uri: Uri, prefix: String): File? = runCatching {
        val input = context.contentResolver.openInputStream(uri) ?: return null
        val file = File(context.cacheDir, "$prefix-${UUID.randomUUID()}.jpg")
        input.use { source -> FileOutputStream(file).use { source.copyTo(it) } }
        file
    }.getOrNull()

    fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
    }.getOrNull()

    /**
     * Converts a locally selected garment into the raw Base64 payload expected
     * by the Decart realtime SDK. The image is rotated according to EXIF,
     * resized to a practical realtime-upload size, and compressed before
     * Base64 encoding. Do not add a `data:image/...;base64,` prefix here: the
     * realtime service rejects it as invalid set_image data.
     */
    fun garmentToBase64(file: File): String {
        require(file.exists() && file.length() > 0L) { "衣服图片不存在或为空" }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        check(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取衣服图片" }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(maxOf(bounds.outWidth, bounds.outHeight), 1600)
        }
        val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
            ?: throw IllegalStateException("无法解码衣服图片")

        val exifRotation = runCatching {
            ExifInterface(file.absolutePath)
                .getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = orientationMatrix(exifRotation)
        val rotated = if (matrix.isIdentity) decoded else {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        if (rotated !== decoded) decoded.recycle()

        val maxSide = maxOf(rotated.width, rotated.height)
        val scaled = if (maxSide <= MAX_UPLOAD_SIDE) {
            rotated
        } else {
            val scale = MAX_UPLOAD_SIDE.toFloat() / maxSide
            Bitmap.createScaledBitmap(
                rotated,
                (rotated.width * scale).toInt().coerceAtLeast(1),
                (rotated.height * scale).toInt().coerceAtLeast(1),
                true,
            ).also {
                if (it !== rotated) rotated.recycle()
            }
        }

        val bytes = ByteArrayOutputStream().use { output ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, output)
            output.toByteArray()
        }
        scaled.recycle()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun calculateSampleSize(longestSide: Int, target: Int): Int {
        var sample = 1
        while (longestSide / (sample * 2) >= target) sample *= 2
        return sample
    }

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
            -> postRotate(180f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                setScale(-1f, 1f)
                postRotate(90f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                setScale(-1f, 1f)
                postRotate(-90f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            else -> Unit
        }
    }

    private fun part(name: String, value: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(name, value)

    private fun filePart(name: String, file: File, mime: String): MultipartBody.Part =
        MultipartBody.Part.createFormData(
            name,
            file.name,
            file.asRequestBody(mime.toMediaTypeOrNull())
        )

    private fun isoNow(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())

    private companion object {
        const val MAX_UPLOAD_SIDE = 1200
    }
}
