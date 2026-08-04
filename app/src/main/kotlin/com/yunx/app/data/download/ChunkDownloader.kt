package com.yunx.app.data.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/**
 * OkHttp 分片下载器：
 * - 支持 Range 分片请求、多线程并行下载；
 * - 断点续传：part 文件已存在部分时从已有大小继续；
 * - 分片完成后按顺序合并为完整文件。
 */
class ChunkDownloader(private val client: OkHttpClient) {

    /**
     * 获取文件总大小：用 Range: bytes=0-0 请求解析 Content-Range 的 total。
     * @return 总字节数；无法获取时返回 null
     */
    suspend fun getTotalSize(url: String, headers: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-0")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                // Content-Range: bytes 0-0/123456
                response.header("Content-Range")
                    ?.substringAfter('/')
                    ?.toLongOrNull()
                    ?: response.header("Content-Length")?.toLongOrNull()
            }
        }.getOrNull()
    }

    /**
     * 下载一个分片到 partFile（断点续传：从 partFile 已有大小继续）。
     * @param onBytes 每读到一段数据回调新增字节数（用于进度上报）
     * @return 是否成功
     */
    suspend fun downloadChunk(
        url: String,
        start: Long,
        end: Long,
        partFile: File,
        headers: Map<String, String>,
        onBytes: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = partFile.length()
        val from = start + existing
        val total = end - start + 1
        // 分片已完整下载
        if (existing >= total) return@withContext true

        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=$from-$end")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        runCatching {
            client.newCall(request).execute().use { response ->
                // 206 分片响应；200 表示服务器忽略 Range（仅允许 start=0 单片场景）
                if (response.code != 206 && !(response.code == 200 && start == 0L)) return@use false
                val body = response.body ?: return@use false
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(existing)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            raf.write(buffer, 0, read)
                            onBytes(read.toLong())
                        }
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    /** 按顺序合并分片为完整文件 */
    suspend fun mergeChunks(chunkFiles: List<File>, target: File): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                chunkFiles.forEach { part ->
                    part.inputStream().use { input ->
                        input.copyTo(out)
                    }
                }
            }
            true
        }.getOrDefault(false)
    }
}