package com.yunx.app.data.download

import android.content.Context
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

/** 实时下载统计（用于 UI 展示速度/剩余时间/线程数） */
data class DownloadStats(
    val speed: Long = 0L,        // 字节/秒
    val remainMillis: Long = -1L, // 剩余时间（毫秒），未知为 -1
    val chunkCount: Int = 1       // 分片（线程）数
)

/**
 * 下载任务管理器：
 * - 任务持久化（Room），状态流转 PENDING → DOWNLOADING → COMPLETED / PAUSED / FAILED；
 * - 分片多线程下载（每片一个协程，信号量限并发）；
 * - 断点续传：part 文件保留，暂停/重启后从已有大小继续；
 * - 完成后合并分片并保存到公共 Download 目录。
 */
class DownloadManager(
    private val context: Context,
    private val dao: DownloadTaskDao,
    private val downloader: ChunkDownloader
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val activeJobs = ConcurrentHashMap<Long, kotlinx.coroutines.Job>()

    /** 实时下载统计（速度/剩余时间/线程数） */
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 默认分片并发数 */
    private val maxConcurrency = 3

    /** 进度上报节流阈值（字节） */
    private val progressThrottle = 512 * 1024L

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 入队并立即开始下载 */
    suspend fun enqueue(url: String, fileName: String, headers: Map<String, String> = emptyMap()): Long {
        // 文件名兜底：空白时从 URL 推导，避免保存时变成时间戳
        val safeName = fileName.ifBlank {
            url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "download_${System.currentTimeMillis()}" }
        }
        val id = dao.insert(
            DownloadTaskEntity(
                url = url,
                fileName = safeName
            )
        )
        start(id, headers)
        return id
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        if (activeJobs.containsKey(id)) return
        val job = scope.launch {
            try {
                runTask(id, headers)
            } catch (e: CancellationException) {
                // 主动暂停：保留 part 文件，状态置为 PAUSED
                _stats.update { it - id }
                dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED)
            } catch (e: Exception) {
                _stats.update { it - id }
                dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
            } finally {
                activeJobs.remove(id)
            }
        }
        activeJobs[id] = job
    }

    /** 暂停下载（保留 part 文件） */
    fun pause(id: Long) {
        activeJobs.remove(id)?.cancel()
        _stats.update { it - id }
        scope.launch { dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED) }
    }

    /** 删除任务：取消下载 + 清 DB + 清 part 文件 */
    fun remove(id: Long) {
        activeJobs.remove(id)?.cancel()
        _stats.update { it - id }
        scope.launch {
            dao.delete(id)
            chunkDirOf(id).deleteRecursively()
        }
    }

    // ---------- 内部实现 ----------

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        val task = dao.get(id) ?: return
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)

        val total = downloader.getTotalSize(task.url, headers)
            ?: throw IllegalStateException("无法获取文件大小")
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, total)

        val chunkCount = chunkCountFor(total)
        val chunkSize = ceil(total.toDouble() / chunkCount).toLong()
        val chunkDir = chunkDirOf(id).apply { mkdirs() }

        // 注册实时统计（线程数=分片数）
        _stats.update { it + (id to DownloadStats(0L, -1L, chunkCount)) }

        // 统计已有 part 大小（断点续传起点）
        val downloaded = AtomicLong(0)
        (0 until chunkCount).forEach { i ->
            downloaded.addAndGet(File(chunkDir, "part_$i").length())
        }
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, downloaded.get(), total)

        val lastUpdate = AtomicLong(downloaded.get())
        val speedRecorder = SpeedRecorder()
        val semaphore = Semaphore(maxConcurrency)

        val allOk = coroutineScope {
            val results = (0 until chunkCount).map { i ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val start = i * chunkSize
                        val end = min(start + chunkSize - 1, total - 1)
                        downloader.downloadChunk(
                            url = task.url,
                            start = start,
                            end = end,
                            partFile = File(chunkDir, "part_$i"),
                            headers = headers
                        ) { bytes ->
                            val new = downloaded.addAndGet(bytes)
                            // 速度/剩余时间统计（每 500ms 更新一次）
                            speedRecorder.onBytes(new)?.let { speed ->
                                val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                _stats.update { it + (id to DownloadStats(speed, remain, chunkCount)) }
                            }
                            val last = lastUpdate.get()
                            if (new - last >= progressThrottle || new >= total) {
                                if (lastUpdate.compareAndSet(last, new)) {
                                    dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
                                }
                            }
                        }
                    }
                }
            }.awaitAll()
            results.all { it }
        }
        if (!allOk) throw IllegalStateException("分片下载失败")

        // 合并分片
        val merged = File(context.cacheDir, "merged_$id")
        val chunkFiles = (0 until chunkCount).map { i -> File(chunkDir, "part_$i") }
        if (!downloader.mergeChunks(chunkFiles, merged)) {
            throw IllegalStateException("合并分片失败")
        }

        // 保存到公共 Download 目录
        val savedPath = DownloadSaver.save(context, task.fileName, merged)
            ?: throw IllegalStateException("保存到下载目录失败")
        dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath)

        // 清理临时文件与统计
        _stats.update { it - id }
        merged.delete()
        chunkDir.deleteRecursively()
    }

    /** 速度采样器：每 500ms 计算一次平均速度 */
    private class SpeedRecorder {
        private var lastBytes = 0L
        private var lastTime = System.currentTimeMillis()

        fun onBytes(total: Long): Long? {
            val now = System.currentTimeMillis()
            val elapsed = now - lastTime
            if (elapsed >= 500) {
                val speed = if (elapsed > 0) {
                    ((total - lastBytes) * 1000 / elapsed).coerceAtLeast(0)
                } else 0L
                lastBytes = total
                lastTime = now
                return speed
            }
            return null
        }
    }

    private fun chunkDirOf(id: Long): File =
        File(context.filesDir, "download_tmp/$id")

    private fun chunkCountFor(total: Long): Int = when {
        total <= 0 -> 1
        total < 5 * 1024 * 1024 -> 1          // < 5MB
        total < 50 * 1024 * 1024 -> 3         // < 50MB
        total < 500 * 1024 * 1024 -> 5        // < 500MB
        else -> 8
    }
}