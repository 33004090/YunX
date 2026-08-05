package com.yunx.app.data.download

import android.content.Context
import com.yunx.app.data.db.DownloadTaskDao
import com.yunx.app.data.db.DownloadTaskEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
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
    private val downloader: ChunkDownloader,
    /** 下载线程数提供者（可在设置中修改，动态生效），默认 16 */
    private val threadProvider: () -> Int = { 16 }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 运行中的任务 Job：value 为 CompletableDeferred，注册/移除全程由 jobsLock 保护，
     * 保证 start/pause/remove 之间无 TOCTOU 竞态（防止"暂停/删除瞬间任务继续跑"）。
     */
    private val activeJobs = ConcurrentHashMap<Long, CompletableDeferred<Job>>()
    private val jobsLock = Any()

    /** 每个任务一把互斥锁：暂停后立即恢复时避免新旧协程并发写分片 */
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    /** 任务请求头（Cookie/UA），暂停后恢复仍需使用 */
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /** 任务下载完成后的清理回调（如删除网盘临时转存文件；下载成功后才触发） */
    private val taskCallbacks = ConcurrentHashMap<Long, suspend () -> Unit>()

    /** 实时下载统计（速度/剩余时间/线程数） */
    private val _stats = MutableStateFlow<Map<Long, DownloadStats>>(emptyMap())
    val stats: StateFlow<Map<Long, DownloadStats>> = _stats.asStateFlow()

    /** 进度上报节流阈值（字节） */
    private val progressThrottle = 512 * 1024L

    val tasks: Flow<List<DownloadTaskEntity>> = dao.observeAll()

    /** 入队并立即开始下载 */
    suspend fun enqueue(
        url: String,
        fileName: String,
        headers: Map<String, String> = emptyMap(),
        /** 下载成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发 */
        onComplete: suspend () -> Unit = {}
    ): Long {
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
        // 保存请求头（Cookie/UA），暂停后恢复仍需携带
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        taskCallbacks[id] = onComplete
        start(id, headers)
        return id
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        // 恢复时未传 headers：沿用入队时保存的（Cookie/UA 对直链下载是必需的）
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        synchronized(jobsLock) {
            // 原子注册：检查 + 占位 + launch + complete 在同一锁内完成，
            // pause/remove 要么拿到已注册的 job，要么拿不到（视为未运行）
            val existing = activeJobs[id]
            if (existing != null) {
                // job 仍活跃（正在下载/收尾）：忽略本次 start，避免重复启动
                if (existing.isCompleted && existing.getCompleted().isActive) return
                // job 已结束但 finally 尚未清理（暂停后立即恢复的残留）：
                // 移除旧引用，继续注册新 job，保证"点开始"立即生效
                activeJobs.remove(id)
            }
            val deferred = CompletableDeferred<Job>()
            activeJobs[id] = deferred
            val job = scope.launch {
                try {
                    // 任务级互斥：同一任务串行执行，暂停后立刻恢复不会并发写分片
                    taskLocks.getOrPut(id) { Mutex() }.withLock {
                        runTask(id, effectiveHeaders)
                    }
                } catch (e: CancellationException) {
                    // 主动暂停/删除：part 文件保留（或由 remove 清理）；状态已由调用方设置
                    _stats.update { it - id }
                } catch (e: Exception) {
                    _stats.update { it - id }
                    // 协程已被取消（暂停/删除）：不标记失败，避免覆盖 PAUSED 状态
                    if (isTaskActive()) {
                        dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(id, e.message ?: e.javaClass.simpleName)
                    }
                } finally {
                    // 只移除自己注册的 deferred：
                    // 若暂停后立即恢复（新 job 已注册到同一 id），不能误删新任务的注册，
                    // 否则新任务将无法再被暂停/删除（后台继续下载）
                    synchronized(jobsLock) {
                        if (activeJobs[id] === deferred) activeJobs.remove(id)
                    }
                    // 注意：taskLocks 不在此清理 —— 若新任务已 getOrPut 拿到锁，
                    // 旧任务 finally 的 remove 会误删新任务的锁导致并发写分片
                }
            }
            // launch 是同步返回 Job 的，锁内 complete，pause/remove 的 await 立即返回
            deferred.complete(job)
        }
    }

    /** 暂停下载（保留 part 文件与请求头） */
    fun pause(id: Long) {
        // 立即中断该任务所有分片网络请求（不依赖协程取消传播，阻塞 IO 马上停止）
        downloader.cancelCalls(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        _stats.update { it - id }
        if (deferred != null) {
            scope.launch {
                // deferred 已在 start 的锁内 complete，await 立即返回；
                // cancel 触发协程退出（网络已由 cancelCalls 中断）
                deferred.await().cancel()
            }
        }
        scope.launch { dao.updateStatus(id, DownloadTaskEntity.STATUS_PAUSED) }
    }

    /**
     * 删除任务：取消下载 + 清 DB + 清 part 文件。
     * @param deleteLocal 同时删除已保存到本地的文件（savePath）
     */
    fun remove(id: Long, deleteLocal: Boolean = false) {
        // 立即中断该任务所有分片网络请求
        downloader.cancelCalls(id)
        _stats.update { it - id }
        taskHeaders.remove(id)
        taskCallbacks.remove(id)
        taskLocks.remove(id)
        val deferred = synchronized(jobsLock) { activeJobs.remove(id) }
        scope.launch {
            // 若任务正在下载：取消并等待协程真正退出，
            // 确保没有后台残留下载、part 文件无 fd 占用（否则删了仍占空间）
            if (deferred != null) {
                deferred.await().cancelAndJoin()
            }
            if (deleteLocal) {
                dao.get(id)?.savePath?.let { DownloadSaver.delete(context, it) }
            }
            dao.delete(id)
            chunkDirOf(id).deleteRecursively()
        }
    }

    // ---------- 内部实现 ----------

    /** 当前协程是否仍活跃（暂停/删除触发取消后为 false） */
    private suspend fun isTaskActive(): Boolean = coroutineContext[Job]?.isActive == true

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        // 协程已被取消（暂停/删除）：直接退出，不写状态
        if (!isTaskActive()) return
        val task = dao.get(id) ?: return
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)

        val total = downloader.getTotalSize(task.url, headers)
            ?: throw IllegalStateException("无法获取文件大小")
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, total)
        // 取到大小后再次检查取消（暂停可能发生在 getTotalSize 期间）
        if (!isTaskActive()) return

        val chunkCount = chunkCountFor(total)
        val chunkSize = ceil(total.toDouble() / chunkCount).toLong()
        val chunkDir = chunkDirOf(id).apply { mkdirs() }

        // 注册实时统计：线程数 = 用户设置的线程数（非分片数）
        val threadCount = threadProvider().coerceAtLeast(1)
        _stats.update { it + (id to DownloadStats(0L, -1L, threadCount)) }

        // 统计已有 part 大小（断点续传起点）
        val downloaded = AtomicLong(0)
        (0 until chunkCount).forEach { i ->
            downloaded.addAndGet(File(chunkDir, "part_$i").length())
        }
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, downloaded.get(), total)

        val lastUpdate = AtomicLong(downloaded.get())
        val speedRecorder = SpeedRecorder()
        val semaphore = Semaphore(threadCount)
        // 记录第一个失败分片的具体原因
        val failedReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

        val allOk = coroutineScope {
            val results = (0 until chunkCount).map { i ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val start = i * chunkSize
                        val end = min(start + chunkSize - 1, total - 1)
                        try {
                            downloader.downloadChunk(
                                taskId = id,
                                url = task.url,
                                start = start,
                                end = end,
                                partFile = File(chunkDir, "part_$i"),
                                headers = headers
                            ) { bytes ->
                                val new = downloaded.addAndGet(bytes)
                                // 暂停/删除已触发取消：跳过进度上报与状态更新，
                                // 避免把 PAUSED 覆盖回 DOWNLOADING（"暂停后闪一下又开始了"）
                                if (!isTaskActive()) return@downloadChunk
                                // 速度/剩余时间统计（每 500ms 更新一次）
                                speedRecorder.onBytes(new)?.let { speed ->
                                    val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                    _stats.update { it + (id to DownloadStats(speed, remain, threadCount)) }
                                }
                                val last = lastUpdate.get()
                                if (new - last >= progressThrottle || new >= total) {
                                    if (lastUpdate.compareAndSet(last, new)) {
                                        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            failedReason.compareAndSet(null, "分片 ${i + 1}/$chunkCount：${e.message ?: e.javaClass.simpleName}")
                            false
                        }
                    }
                }
            }.awaitAll()
            results.all { it }
        }
        if (!allOk) throw IllegalStateException(failedReason.get() ?: "分片下载失败")
        // 下载完成但已取消（删除/暂停发生在合并前）：不再合并保存
        if (!isTaskActive()) return

        // 合并分片
        val merged = File(context.cacheDir, "merged_$id")
        val chunkFiles = (0 until chunkCount).map { i -> File(chunkDir, "part_$i") }
        if (!downloader.mergeChunks(chunkFiles, merged)) {
            throw IllegalStateException("合并分片失败")
        }
        // 合并完成但已取消（删除发生在合并期间）：不保存，避免向公共目录写入残留文件
        if (!isTaskActive()) return

        // 保存到公共 Download 目录
        val savedPath = DownloadSaver.save(context, task.fileName, merged)
            ?: throw IllegalStateException("保存到下载目录失败")
        dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath)

        // 下载成功：触发清理回调（删除网盘临时转存文件等）；失败/取消不触发
        taskCallbacks.remove(id)?.let { cb ->
            runCatching { cb() }
        }

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
        total < 50 * 1024 * 1024 -> 4         // < 50MB
        total < 500 * 1024 * 1024 -> 8        // < 500MB
        else -> 16                            // ≥ 500MB，配合高线程数
    }
}