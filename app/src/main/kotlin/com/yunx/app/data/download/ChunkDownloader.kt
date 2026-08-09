package com.yunx.app.data.download

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

private const val TAG = "YunX-DL"

/** 分片下载单次失败后的重试次数（瞬时 IO 抖动自动恢复，结构性失败不重试） */
private const val CHUNK_RETRIES = 3

/** 网络读缓冲：256KB，降低大文件下载的 syscall 次数 */
private const val BUFFER_SIZE = 256 * 1024

/**
 * OkHttp 分片下载器：
 * - 支持 Range 分片请求、多线程并行下载；
 * - 断点续传：part 文件已存在部分时从已有大小继续；
 * - 分片完成后按顺序合并为完整文件；
 * - 任务级取消：每个任务的 OkHttp Call 统一登记，暂停/删除时主动 cancel() 立即中断阻塞 IO。
 */
class ChunkDownloader(private val client: OkHttpClient) {

    /** 任务 id → 该任务当前所有分片请求（供暂停/删除时立即中断网络） */
    private val activeCalls = ConcurrentHashMap<Long, MutableSet<Call>>()
    /** 取消指定任务的所有分片请求（立即中断阻塞 IO，不依赖协程取消传播） */
    fun cancelCalls(taskId: Long) {
        activeCalls.remove(taskId)?.forEach { call ->
            runCatching { call.cancel() }
        }
    }

    /**
     * 获取文件总大小：先试 Range: bytes=0-0（解析 Content-Range），
     * 失败后再试无 Range 的 GET（读 Content-Length；部分 CDN 忽略 Range 返回 200 全量）。
     * @return 总字节数；无法获取时返回 null
     */
    suspend fun getTotalSize(url: String, headers: Map<String, String>): Long? = withContext(Dispatchers.IO) {
        val withRange = probeSize(url, headers, withRange = true)
        if (withRange != null) return@withContext withRange
        probeSize(url, headers, withRange = false)
    }

    private suspend fun probeSize(url: String, headers: Map<String, String>, withRange: Boolean): Long? =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply {
                    if (withRange) header("Range", "bytes=0-0")
                    headers.forEach { (k, v) -> header(k, v) }
                }
                .get()
                .build()
            val call = client.newCall(request)
            val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
            try {
                runCatching {
                    call.execute().use { response ->
                        Log.d(TAG, "getTotalSize: range=$withRange code=${response.code} url=${url.take(120)}")
                        if (!response.isSuccessful) return@use null
                        // Content-Range: bytes 0-0/123456
                        response.header("Content-Range")
                            ?.substringAfter('/')
                            ?.toLongOrNull()
                            ?: response.header("Content-Length")?.toLongOrNull()
                    }
                }.getOrNull()
            } finally {
                cancelHandle?.dispose()
            }
        }

    /**
     * 下载一个分片到 partFile（断点续传：从 partFile 已有大小继续）。
     * - 瞬时 IO 异常（弱网抖动）自动重试（指数退避），结构性失败（非 206 等）不重试；
     * - 每轮重试都基于 partFile 当前大小续传，已下载数据不丢弃。
     * @param onBytes 每读到一段数据回调新增字节数（用于进度上报）
     * @return 是否成功
     */
    suspend fun downloadChunk(
        taskId: Long,
        url: String,
        start: Long,
        end: Long,
        partFile: File,
        headers: Map<String, String>,
        onBytes: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        repeat(CHUNK_RETRIES) { attempt ->
            // 协程已被取消（暂停/删除）：立即传播，不再重试
            if (!isActive) throw CancellationException("下载被取消", lastError)
            // 每轮重试重新读取 part 大小：断点续传，已下载部分不重下
            val existing = partFile.length()
            val from = start + existing
            // end == Long.MAX_VALUE 表示总大小未知：用开放区间 Range（bytes=from-），读到 EOF
            val unknownTotal = end == Long.MAX_VALUE
            val total = if (unknownTotal) -1L else end - start + 1
            // 分片已完整下载
            if (!unknownTotal && existing >= total) return@withContext true
            Log.d(TAG, "downloadChunk: task=$taskId 尝试${attempt + 1}/$CHUNK_RETRIES range=$from-${if (unknownTotal) "EOF" else end} 已有=$existing")

            val result = try {
                doChunkAttempt(taskId, url, from, end, unknownTotal, partFile, headers, existing, onBytes)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "downloadChunk: task=$taskId 尝试${attempt + 1} IO异常: ${e.message}")
                if (!isActive) throw CancellationException("下载被取消", e)
                null // 瞬时 IO 异常 → 重试
            }
            when (result) {
                true -> return@withContext true
                false -> return@withContext false // 结构性失败（非 206 / body 空等），不重试
                null -> {
                    // 指数退避后重试
                    if (attempt < CHUNK_RETRIES - 1) {
                        delay((500L * (attempt + 1)).coerceAtMost(3000))
                    }
                }
            }
        }
        false
    }

    /** 单次分片请求（不重试）：成功返回 true；请求完成但失败（非 206 等结构性原因）返回 false；IO 异常向上抛出 */
    private suspend fun doChunkAttempt(
        taskId: Long,
        url: String,
        from: Long,
        end: Long,
        unknownTotal: Boolean,
        partFile: File,
        headers: Map<String, String>,
        existing: Long,
        onBytes: suspend (Long) -> Unit
    ): Boolean {
        val request = Request.Builder()
            .url(url)
            .header("Range", if (unknownTotal) "bytes=$from-" else "bytes=$from-$end")
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()

        val call = client.newCall(request)
        // 登记到任务级集合：暂停/删除时 DownloadManager 主动 cancelCalls() 立即中断阻塞 IO
        activeCalls.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(call)
        // 协程取消（暂停/删除）时也立即中断网络请求（双保险）
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            return call.execute().use { response ->
                // 206 分片响应；200 表示服务器忽略 Range（仅允许 start=0 单片场景）
                if (response.code != 206 && !(response.code == 200 && from == 0L)) {
                    Log.w(TAG, "downloadChunk: task=$taskId range=$from-$end 非预期状态码 ${response.code}")
                    return@use false
                }
                Log.d(TAG, "downloadChunk: task=$taskId range=$from-$end code=${response.code} 下载中")
                val body = response.body ?: return@use false
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(existing)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
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
        } finally {
            activeCalls[taskId]?.remove(call)
            cancelHandle?.dispose()
        }
    }

    /** 无 Range 完整下载（回退：部分 CDN 拒绝 Range 请求时使用），读到 EOF */
    suspend fun downloadFull(
        taskId: Long,
        url: String,
        partFile: File,
        headers: Map<String, String>,
        onBytes: suspend (Long) -> Unit
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = partFile.length()
        Log.d(TAG, "downloadFull: task=$taskId 完整下载 url=${url.take(120)} 已有=$existing")
        val request = Request.Builder()
            .url(url)
            .apply { headers.forEach { (k, v) -> header(k, v) } }
            .get()
            .build()
        val call = client.newCall(request)
        activeCalls.getOrPut(taskId) { ConcurrentHashMap.newKeySet() }.add(call)
        val cancelHandle = coroutineContext[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    // 抛带状态码的异常（不被 catch(IOException) 吞掉），让任务失败信息可见真实 HTTP 码
                    throw IllegalStateException("下载失败 HTTP ${response.code}")
                }
                val body = response.body ?: return@use false
                RandomAccessFile(partFile, "rw").use { raf ->
                    raf.seek(existing)
                    body.byteStream().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
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
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "downloadFull: task=$taskId IO异常: ${e.message}")
            if (!isActive) throw CancellationException("下载被取消", e)
            false
        } finally {
            activeCalls[taskId]?.remove(call)
            cancelHandle?.dispose()
        }
    }

    /** 按顺序合并分片为完整文件（FileChannel.transferTo 零拷贝，替代 8KB copyTo） */
    suspend fun mergeChunks(chunkFiles: List<File>, target: File): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            target.parentFile?.mkdirs()
            FileOutputStream(target).use { fos ->
                fos.channel.use { out ->
                    chunkFiles.forEach { part ->
                        FileInputStream(part).use { fis ->
                            fis.channel.use { inCh ->
                                var pos = 0L
                                val size = inCh.size()
                                while (pos < size) pos += inCh.transferTo(pos, size - pos, out)
                            }
                        }
                    }
                }
            }
            true
        }.getOrDefault(false)
        Log.d(TAG, "mergeChunks: parts=${chunkFiles.size} target=$target ok=$ok")
        ok
    }
}