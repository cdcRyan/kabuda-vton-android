package ai.anywear.mvp.recording

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class SessionMetadata(
    val sessionId: String,
    val installationId: String,
    val garmentFileName: String?,
    val startedAt: Long,
    val endedAt: Long,
    val state: String,
    val note: String = "MVP archive: selected garment and optional rendered frames."
)

object SessionArchive {
    private val gson = GsonBuilder().setPrettyPrinting().create()

    fun create(
        context: Context,
        metadata: SessionMetadata,
        garmentFile: File?,
        inputFrame: File?,
        outputFrame: File?,
        videoFile: File? = null
    ): File? = runCatching {
        val sessionDir = File(context.cacheDir, "sessions").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val archive = File(sessionDir, "anywear-session-$stamp.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry("session.json"))
            zip.write(gson.toJson(metadata).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            copyEntry(zip, garmentFile, "garment.jpg")
            copyEntry(zip, inputFrame, "input-frame.jpg")
            copyEntry(zip, outputFrame, "output-frame.jpg")
            copyEntry(zip, videoFile, "camera-video.mp4")
        }
        archive
    }.getOrNull()

    fun share(context: Context, archive: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            archive
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 Anywear 会话"))
    }

    fun captureBitmapToCache(context: Context, bitmap: Bitmap?, prefix: String): File? {
        if (bitmap == null) return null
        return runCatching {
            val dir = File(context.cacheDir, "frames").apply { mkdirs() }
            val file = File(dir, "$prefix-${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            file
        }.getOrNull()
    }

    private fun copyEntry(zip: ZipOutputStream, file: File?, entryName: String) {
        if (file == null || !file.exists()) return
        zip.putNextEntry(ZipEntry(entryName))
        FileInputStream(file).use { it.copyTo(zip) }
        zip.closeEntry()
    }
}
