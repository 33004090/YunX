package com.yunx.app.util

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * 日志导出工具：
 * 1. 头部写入应用 / 设备信息；
 * 2. `logcat -d -v time` dump 全部运行日志（主缓冲区）；
 * 3. `logcat -b crash -d -v time` dump 崩溃日志（crash 缓冲区）；
 * 4. 合并写入 cacheDir/logs/ 下文本文件，通过 FileProvider + 系统分享导出。
 */
object LogExporter {

    private const val EXPORT_DIR = "logs"

    /** 单缓冲区最多保留的行数（防止超大 buffer 导致内存/文件过大） */
    private const val MAX_LINES = 30000

    /** 生成日志文件并返回；失败返回 null（不抛异常） */
    fun export(context: Context): File? = runCatching {
        val dir = File(context.cacheDir, EXPORT_DIR).apply { mkdirs() }
        val out = File(dir, "yunx_log_${timestamp()}.txt")

        OutputStreamWriter(FileOutputStream(out), StandardCharsets.UTF_8).use { writer ->
            // ---------- 头部：应用与设备信息 ----------
            val pkg = context.packageManager.getPackageInfo(context.packageName, 0)
            writer.write("云析（YunX）日志导出\n")
            writer.write("导出时间：${now()}\n")
            writer.write("应用版本：${pkg.versionName}（${pkg.versionCode}）\n")
            writer.write("设备：${Build.MANUFACTURER} ${Build.MODEL}\n")
            writer.write("系统：Android ${Build.VERSION.RELEASE}（SDK ${Build.VERSION.SDK_INT}）\n")
            writer.write("\n")

            // ---------- 运行日志：logcat 主缓冲区 ----------
            writer.write("========== 运行日志（logcat -d -v time）==========\n")
            dumpLogcat(writer, listOf("logcat", "-d", "-v", "time"))
            writer.write("\n")

            // ---------- 崩溃日志：logcat crash 缓冲区 ----------
            writer.write("========== 崩溃日志（logcat -b crash -d -v time）==========\n")
            dumpLogcat(writer, listOf("logcat", "-b", "crash", "-d", "-v", "time"))
        }
        out
    }.getOrNull()

    /** 执行 logcat 命令并写入 writer（仅保留最近 MAX_LINES 行） */
    private fun dumpLogcat(writer: OutputStreamWriter, command: List<String>) {
        var process: Process? = null
        try {
            process = ProcessBuilder(command).redirectErrorStream(true).start()
            val reader =
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))

            // 环形缓冲：只保留最近 MAX_LINES 行
            val lines = ArrayDeque<String>()
            var line: String? = reader.readLine()
            while (line != null) {
                lines.addLast(line)
                if (lines.size > MAX_LINES) lines.removeFirst()
                line = reader.readLine()
            }
            process.waitFor()

            if (lines.isEmpty()) {
                writer.write("（无输出）\n")
            } else {
                lines.forEach { writer.write(it); writer.write("\n") }
            }
        } catch (e: Exception) {
            writer.write("（读取日志失败：${e.message}）\n")
        } finally {
            try {
                process?.destroy()
            } catch (_: Exception) {
            }
        }
    }

    /** 通过系统分享导出日志文件；成功返回 true */
    fun share(context: Context, file: File): Boolean = runCatching {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // chooser 外层也带上读权限 flag：部分接收者（文件管理器/系统 UI）通过
        // 自己的 Intent 读取 uri 时需要授权，否则报 Permission Denial
        val chooser = Intent.createChooser(intent, "分享日志").apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(chooser)
        true
    }.getOrElse {
        false
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
}