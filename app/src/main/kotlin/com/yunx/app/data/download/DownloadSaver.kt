package com.yunx.app.data.download

import android.content.ContentResolver
import android.content.ContentUris
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
 * 幽灵文件（文件已删但 MediaStore 残留）导致同名 insert 失败时，自动加时间戳防重保存。
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
        // Android 10+ 优先 MediaStore；失败则回退传统文件路径（Android 9- 可用）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, safeName, safeDir, source)?.let { return it }
            Log.e(TAG, "MediaStore 保存失败，回退传统路径：$safeDir/$safeName")
        }
        saveLegacy(context, safeName, safeDir, source)?.let { return it }
        Log.e(TAG, "传统路径保存失败（Android 9- 需存储权限；Android 10+ 分区存储不可写），放弃保存")
        return null
    }

    /** 清洗文件名：非法字符替换为下划线，超长截断（保留扩展名），空名兜底 */
    private fun sanitizeFileName(name: String): String {
        var cleaned = name
            .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_")
            .trim()
        if (cleaned.length > 120) {
            val ext = cleaned.substringAfterLast('.', "").take(10)
            val base = cleaned.substringBeforeLast('.').take(100)
            cleaned = if (ext.isNotBlank() && ext != cleaned) "$base.$ext" else cleaned.take(120)
        }
        return cleaned.ifBlank { "download_${System.currentTimeMillis()}" }
    }

    /**
     * MediaStore.Downloads 保存：
     * 1. 保存前清理同路径同名残留记录（幽灵文件：文件已删但数据库仍在，部分 ROM 同名 insert 会返回 null）；
     * 2. 原名 insert → 失败则在扩展名前加时间戳防重（最多 3 次，绕过幽灵/同名约束）；
     * 3. 均失败返回 null（上层报错，不再兜底私有目录）。
     */
    private fun saveViaMediaStore(context: Context, fileName: String, subDir: String, source: File): String? {
        val resolver = context.contentResolver
        val relativePath = if (subDir.isBlank()) {
            Environment.DIRECTORY_DOWNLOADS
        } else {
            "${Environment.DIRECTORY_DOWNLOADS}/$subDir"
        }
        // 幽灵文件处理：保存前先清理同名残留记录
        removeMediaStoreDuplicates(resolver, fileName, relativePath)
        // 候选：原名 → 时间戳防重名（base.apk → base_20260812165000.apk → base_..._2.apk）
        val candidates = buildList {
            add(fileName)
            repeat(3) { i -> add(timestampedName(fileName, i)) }
        }
        for (candidate in candidates) {
            try {
                removeMediaStoreDuplicates(resolver, candidate, relativePath)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, candidate)
                    put(MediaStore.Downloads.MIME_TYPE, mimeOf(candidate))
                    put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
                val wrote = resolver.openOutputStream(uri)?.use { out ->
                    source.inputStream().use { it.copyTo(out) }
                    true
                } ?: run {
                    resolver.delete(uri, null, null)
                    false
                }
                if (!wrote) continue
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "MediaStore 保存异常（$candidate）: ${e.message}")
            }
        }
        return null
    }

    /** 在文件名扩展名前加时间戳防重：base.apk → base_20260812165000.apk */
    private fun timestampedName(fileName: String, attempt: Int): String {
        val dot = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext = if (dot > 0) fileName.substring(dot) else ""
        val ts = System.currentTimeMillis()
        return if (attempt == 0) "${base}_$ts$ext" else "${base}_${ts}_${attempt + 1}$ext"
    }

    /**
     * 清理 MediaStore 中指定路径下的同名记录（幽灵文件：文件已删但数据库残留）。
     * 只删数据库记录及对应文件（若仍存在）；随后可安全插入同名新记录。
     */
    private fun removeMediaStoreDuplicates(
        resolver: ContentResolver,
        fileName: String,
        relativePath: String
    ) {
        runCatching {
            val selection = "${MediaStore.Downloads.DISPLAY_NAME}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
            val projection = arrayOf(MediaStore.Downloads._ID)
            resolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(fileName, relativePath),
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    resolver.delete(uri, null, null)
                }
            }
        }.onFailure {
            Log.e(TAG, "清理 MediaStore 同名记录失败: ${it.message}")
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