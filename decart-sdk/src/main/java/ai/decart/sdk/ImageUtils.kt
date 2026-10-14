package ai.decart.sdk

import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream

object ImageUtils {
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 90): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }

    fun byteArrayToBase64(bytes: ByteArray): String {
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
