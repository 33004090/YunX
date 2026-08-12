package com.yunx.app.data.download

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 完成文件保存到公共 Download 目录：
 * - Android 10+（Q）：MediaStore.Downloads，无需存储权限；
 * - Android 9-：Environment.getExternalStoragePublicDirectory + WRITE_EXTERNAL_STORAGE。
 */
object DownloadSaver {

    private const val TAG = "YunX-DL"

    /**
     * 保存文件到公共 Download 目录。
     * @param fileName 可为**相对路径**（如 "文件夹A/子/文件.mp4"，用于下载整个文件夹保持目录结构）；
     *                 纯文件名时保存到 Download 根目录。
     * @return 保存成功后的标识（MediaStore uri 字符串或文件绝对路径）；失败返回 null
     */
    fun save(context: Context, fileName: String, source: File): String? {
        // 拆分相对路径与文件名：目录段与文件名分别清洗
        val clean = fileName.replace('\\', '/')
        val slash = clean.lastIndexOf('/')
        val dirRel = if (slash > 0) clean.substring(0, slash) else ""
        val baseName = if (slash >= 0) clean.substring(slash + 1) else clean
        val safeName = sanitizeFileName(baseName)
        val safeDir = dirRel.split('/').filter { it.isNotBlank() }
            .joinToString("/") { sanitizeFileName(it) }
        // Android 10+ 优先 MediaStore；失败则回退传统文件路径；再失败兜底应用私有下载目录（保证不报错）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, safeName, safeDir, source)?.let { return it }
            Log.e(TAG, "MediaStore 保存失败，回退传统路径：$safeDir/$safeName")
        }
        saveLegacy(context, safeName, safeDir, source)?.let { return it }
        Log.e(TAG, "传统路径保存失败，兜底应用私有下载目录：$safeDir/$safeName")
        return saveToAppDir(context, safeName, safeDir, source)
    }

    /** 最后兜底：保存到应用私有外部下载目录（用户可在下载页点「打开」访问） */
    private fun saveToAppDir(context: Context, fileName: String, subDir: String, source: File): String? =
        runCatching {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            val destDir = if (subDir.isBlank()) dir else File(dir, subDir)
            destDir.mkdirs()
            val dest = File(destDir, fileName)
            source.copyTo(dest, overwrite = true)
            dest.absolutePath
        }.getOrNull()

    /** 清洗文件名：非法字符替换为下划线，超长截断（保留扩展名），空名兜底 */
    private fun sanitizeFileName(name: String): String {
        var cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
        // 文件系统/MediaStore 对文件名长度有限制：截断到合理长度并保留扩展名
        if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast('.', "").take(10)
            val base = cleaned.substringBeforeLast('.').take(100)
            cleaned = if (ext.isNotBlank() && ext != cleaned) "$base.$ext" else cleaned.take(120)
        }
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    private fun saveViaMediaStore(context: Context, fileName: String, subDir: String, source: File): String? {
        return try {
            val resolver = context.contentResolver
            val relativePath = if (subDir.isBlank()) {
                Environment.DIRECTORY_DOWNLOADS
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/$subDir"
            }
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeOf(fileName))
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: run {
                    Log.e(TAG, "MediaStore insert 返回 null")
                    return null
                }
            resolver.openOutputStream(uri)?.use { out ->
                source.inputStream().use { it.copyTo(out) }
            } ?: run {
                Log.e(TAG, "MediaStore openOutputStream 失败")
                resolver.delete(uri, null, null)
                return null
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "MediaStore 保存异常: ${e.message}")
            null
        }
    }

    private fun saveLegacy(context: Context, fileName: String, subDir: String, source: File): String? = runCatching {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val destDir = if (subDir.isBlank()) dir else File(dir, subDir)
        if (!destDir.exists()) destDir.mkdirs()
        val dest = File(destDir, fileName)
        source.copyTo(dest, overwrite = true)
        dest.absolutePath
    }.getOrNull()

    /**
     * 删除已保存的本地文件（配合任务删除）。
     * @param savePath 保存时返回的 MediaStore uri 字符串或文件绝对路径
     */
    fun delete(context: Context, savePath: String) {
        if (savePath.isBlank()) return
        runCatching {
            if (savePath.startsWith("content://")) {
                context.contentResolver.delete(android.net.Uri.parse(savePath), null, null)
            } else {
                File(savePath).delete()
            }
        }
    }

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