package com.yunx.app.data.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * 完成文件保存到公共 Download 目录：
 * - Android 10+（Q）：MediaStore.Downloads，无需存储权限；
 * - Android 9-：Environment.getExternalStoragePublicDirectory + WRITE_EXTERNAL_STORAGE。
 */
object DownloadSaver {

    /**
     * @return 保存成功后的标识（MediaStore uri 字符串或文件绝对路径）；失败返回 null
     */
    fun save(context: Context, fileName: String, source: File): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, fileName, source)
        } else {
            saveLegacy(context, fileName, source)
        }

    private fun saveViaMediaStore(context: Context, fileName: String, source: File): String? = runCatching {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { it.copyTo(out) }
        } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        uri.toString()
    }.getOrNull()

    private fun saveLegacy(context: Context, fileName: String, source: File): String? = runCatching {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val dest = File(dir, fileName)
        source.copyTo(dest, overwrite = true)
        dest.absolutePath
    }.getOrNull()

    private fun mimeOf(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "pdf" -> "application/pdf"
            "zip", "rar", "7z" -> "application/zip"
            "mp4", "mkv", "mov", "avi", "webm" -> "video/mp4"
            "mp3", "wav", "flac", "aac" -> "audio/mpeg"
            "jpg", "jpeg", "png", "gif", "webp" -> "image/jpeg"
            "txt", "md", "log" -> "text/plain"
            else -> "application/octet-stream"
        }
    }
}