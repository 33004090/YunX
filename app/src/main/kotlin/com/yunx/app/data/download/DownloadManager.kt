package com.yunx.app.data.download

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

private const val TAG = "YunX-DL"

/** 单文件 Range 分片的安全并发上限。迅雷等 CDN 对单文件并发 Range 有阈值，
 *  超过约 8 个并发会把多余请求降级为 200 整文件（忽略 Range），
 *  进而触发整任务回退单流、速度暴跌。压在安全上限内，所有分片都能稳定拿到 206。 */
private const val RANGE_WORKERS_CAP = 8

/** 错峰建连上限（序号）：第 i 个分片首次请求前延迟 (min(i, STAGGER_CAP) * STAGGER_MS) */
private const val STAGGER_CAP = 8
private const val STAGGER_MS = 25L

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
    private val threadProvider: () -> Int = { 16 },
    /** 自定义下载保存目录提供者（SAF tree Uri，可空）；null 时保存到系统默认 Download */
    private val saveDirProvider: () -> String? = { null },
    /** 最大同时下载任务数提供者（默认 3）：限制后台并发任务，避免占满带宽/耗尽路由器连接 */
    private val concurrencyProvider: () -> Int = { 3 },
    /** 全局下载速度限制提供者（字节/秒；0 = 不限速） */
    private val speedLimitProvider: () -> Long = { 0L },
    /** 下载失败后自动重试次数提供者（默认 3，上限 10） */
    private val retryCountProvider: () -> Int = { 3 }
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 当前实际下载中的任务数（用于最大同时下载任务数限制） */
    private val activeDownloads = java.util.concurrent.atomic.AtomicInteger(0)

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒 */
    private val speedLimiter = SpeedLimiter()

    /**
     * 保存前存储权限检查（Android 9- 写公共 Download 需 WRITE_EXTERNAL_STORAGE 运行时授权）。
     * UI 层注入：无权限时动态申请并等待授权结果；已授权/Android 10+ 直接返回 true。
     * 授权后会自动继续保存（同一协程 await 授权结果再往下走）。
     */
    var storagePermissionProvider: suspend () -> Boolean = { true }

    /**
     * 运行中的任务 Job：value 为 CompletableDeferred，注册/移除全程由 jobsLock 保护，
     * 保证 start/pause/remove 之间无 TOCTOU 竞态（防止"暂停/删除瞬间任务继续跑"）。
     */
    private val activeJobs = ConcurrentHashMap<Long, CompletableDeferred<Job>>()
    private val jobsLock = Any()

    /** 前台服务计数：有任务在下载时保持前台（避免切后台限速/进程被杀） */
    private val activeTaskCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 前台通知进度节流（毫秒）：2 秒更新一次，避免频繁刷新系统通知 */
    private val notifyThrottleMs = 2000L
    private val lastNotifyTs = AtomicLong(0)

    /** 更新前台通知进度（2 秒节流；total<=0 时不确定进度，只更新标题） */
    private fun notifyProgress(fileName: String, new: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNotifyTs.get() >= notifyThrottleMs) {
            lastNotifyTs.set(now)
            val percent = if (total > 0) ((new * 100 / total).toInt().coerceIn(0, 100)) else -1
            DownloadService.update(context, fileName, percent)
        }
    }

    /** 每个任务一把互斥锁：暂停后立即恢复时避免新旧协程并发写分片 */
    private val taskLocks = ConcurrentHashMap<Long, Mutex>()

    /** 任务请求头（Cookie/UA），暂停后恢复仍需使用 */
    private val taskHeaders = ConcurrentHashMap<Long, Map<String, String>>()

    /** 已知文件大小（API 返回，避免探测失败）；-1 表示未知 */
    private val taskSizes = ConcurrentHashMap<Long, Long>()

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
        /** 已知文件大小（字节）；-1 表示未知，需探测 */
        size: Long = -1L,
        /** 下载成功完成后的清理回调（如删除网盘临时转存文件）；失败/取消不触发 */
        onComplete: suspend () -> Unit = {}
    ): Long {
        // 文件名兜底：空白时从 URL 推导，避免保存时变成时间戳
        val safeName = fileName.ifBlank {
            url.substringAfterLast('/').substringBefore('?')
                .ifBlank { "download_${System.currentTimeMillis()}" }
        }
        Log.d(TAG, "enqueue: url=$url fileName=$safeName headers=${headers.keys} size=$size")
        val id = dao.insert(
            DownloadTaskEntity(
                url = url,
                fileName = safeName
            )
        )
        // 保存请求头（Cookie/UA），暂停后恢复仍需携带
        if (headers.isNotEmpty()) taskHeaders[id] = headers
        if (size > 0) taskSizes[id] = size
        taskCallbacks[id] = onComplete
        start(id, headers)
        return id
    }

    /** 开始/恢复下载（断点续传） */
    fun start(id: Long, headers: Map<String, String> = emptyMap()) {
        // 恢复时未传 headers：沿用入队时保存的（Cookie/UA 对直链下载是必需的）
        val effectiveHeaders = headers.ifEmpty { taskHeaders[id] ?: emptyMap() }
        Log.d(TAG, "start: id=$id headers=${effectiveHeaders.keys}")
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
                    // 任务开始：有任务在下载时保持前台服务（避免切后台限速/进程被杀）
                    onTaskStarted(id)
                    // 任务级互斥：同一任务串行执行，暂停后立刻恢复不会并发写分片
                    taskLocks.getOrPut(id) { Mutex() }.withLock {
                        runTaskWithRetry(id, effectiveHeaders)
                    }
                } catch (e: CancellationException) {
                    // 主动暂停/删除：part 文件保留（或由 remove 清理）；状态已由调用方设置
                    _stats.update { it - id }
                } catch (e: Exception) {
                    _stats.update { it - id }
                    // 协程已被取消（暂停/删除）：不标记失败，避免覆盖 PAUSED 状态
                    if (isTaskActive()) {
                        Log.e(TAG, "task $id failed: ${e.message ?: e.javaClass.simpleName}", e)
                        dao.updateStatus(id, DownloadTaskEntity.STATUS_FAILED)
                        dao.updateError(id, e.message ?: e.javaClass.simpleName)
                    } else {
                        Log.w(TAG, "task $id cancelled: ${e.message}")
                    }
                } finally {
                    // 任务结束（成功/失败/暂停/删除）：无任务时停止前台服务
                    onTaskFinished()
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

    /** 任务开始/结束计数：控制前台服务生命周期（有任务在下载即保持前台） */
    private suspend fun onTaskStarted(id: Long) {
        if (activeTaskCount.getAndIncrement() == 0) {
            val name = runCatching { dao.get(id)?.fileName }.getOrNull() ?: "下载任务"
            DownloadService.start(context, name)
        }
    }

    private fun onTaskFinished() {
        if (activeTaskCount.decrementAndGet() <= 0) {
            activeTaskCount.set(0)
            DownloadService.stop(context)
        }
    }

    /** 暂停下载（保留 part 文件与请求头） */
    fun pause(id: Long) {
        Log.d(TAG, "pause: id=$id")
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
        Log.d(TAG, "remove: id=$id deleteLocal=$deleteLocal")
        // 立即中断该任务所有分片网络请求
        downloader.cancelCalls(id)
        _stats.update { it - id }
        taskHeaders.remove(id)
        // 删除任务同样触发清理回调（如删除网盘临时转存文件）：
        // 用户放弃下载时云盘里已转存的临时文件也应一并清理
        val cleanup = taskCallbacks.remove(id)
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
            // 删除任务后清理云盘转存（与下载成功完成同语义）；失败不阻断
            cleanup?.let { runCatching { it() } }
        }
    }

    // ---------- 内部实现 ----------

    /** 当前协程是否仍活跃（暂停/删除触发取消后为 false） */
    private suspend fun isTaskActive(): Boolean = coroutineContext[Job]?.isActive == true

    /** 等待并发许可：当前下载任务数 >= 上限时轮询等待（暂停/取消可退出等待） */
    private suspend fun awaitConcurrencySlot() {
        val max = concurrencyProvider().coerceAtLeast(1)
        while (isTaskActive() && activeDownloads.get() >= max) {
            delay(300)
        }
    }

    /**
     * 执行任务并支持失败自动重试（断点续传，part 文件保留）。
     * 同时负责「最大同时下载任务数」并发许可的获取/释放。
     */
    private suspend fun runTaskWithRetry(id: Long, headers: Map<String, String>) {
        var attempts = 0
        val maxRetries = retryCountProvider().coerceIn(0, 10)
        while (true) {
            // 并发许可：排队等待，直到有空闲下载槽位（或任务被暂停/取消）
            awaitConcurrencySlot()
            if (!isTaskActive()) return
            activeDownloads.incrementAndGet()
            try {
                try {
                    runTask(id, headers)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    attempts++
                    if (isTaskActive() && attempts <= maxRetries) {
                        Log.d(TAG, "runTaskWithRetry: id=$id 失败，自动重试 $attempts/$maxRetries：${e.message}")
                        // 逐次递增延迟，避免失败风暴
                        delay(1200L * attempts)
                    } else {
                        throw e
                    }
                }
            } finally {
                activeDownloads.decrementAndGet()
            }
        }
    }

    private suspend fun runTask(id: Long, headers: Map<String, String>) {
        // 协程已被取消（暂停/删除）：直接退出，不写状态
        if (!isTaskActive()) return
        val task = dao.get(id) ?: return
        dao.updateStatus(id, DownloadTaskEntity.STATUS_DOWNLOADING)
        Log.d(TAG, "runTask: id=$id fileName=${task.fileName}")

        // HLS（m3u8 转码流，如 UC play）：不走 Range 分片，直接拉分片合并
        if (task.url.contains(".m3u8", true) || task.url.contains(".m3u", true)) {
            Log.d(TAG, "runTask: id=$id HLS 转码流下载 url=${task.url.take(120)}")
            hlsDownload(id, task, headers)
            return
        }

        // 总大小以服务器探测为准（Range0-0 的 Content-Range 是真实总大小），
        // 避免各平台传入的 size 与实际不符导致分片区间错误 → 文件截断/膨胀损坏
        val total = downloader.getTotalSize(task.url, headers)
            ?: taskSizes[id]?.takeIf { it > 0 }
        if (total == null) {
            // 服务器不返回文件大小（Range/Content-Length 均缺失）：降级为流式下载（开放区间 Range）
            Log.w(TAG, "runTask: id=$id 无法获取总大小，降级流式下载 url=${task.url.take(120)}")
            streamDownload(id, task, headers)
            return
        }
        Log.d(TAG, "getTotalSize: id=$id total=$total url=${task.url.take(120)}")
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, total)
        // 取到大小后再次检查取消（暂停可能发生在 getTotalSize 期间）
        if (!isTaskActive()) return

        val threadCount = threadProvider().coerceAtLeast(1)
        val chunkCount = chunkCountFor(total, threadCount)
        val chunkSize = ceil(total.toDouble() / chunkCount).toLong()
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        // 有效并发：仅迅雷（CDN 对单文件并发 Range 有阈值，约 8 个，超过会降级 200 整文件）封顶安全上限；
        // 其他平台保持用户设置的线程数（满并发）
        val isXunlei = headers["User-Agent"]?.contains("xunlei", ignoreCase = true) == true ||
            task.url.contains("xunlei", ignoreCase = true)
        val effectiveWorkers = if (isXunlei) {
            min(threadCount, RANGE_WORKERS_CAP).coerceAtLeast(1)
        } else {
            threadCount.coerceAtLeast(1)
        }
        Log.d(TAG, "分片规划: id=$id chunks=$chunkCount size=$chunkSize threads=$threadCount effectiveWorkers=$effectiveWorkers isXunlei=$isXunlei")

        // 注册实时统计：线程数 = 有效并发（受安全上限约束）
        _stats.update { it + (id to DownloadStats(0L, -1L, effectiveWorkers)) }

        // 统计已有 part 大小（断点续传起点）
        val downloaded = AtomicLong(0)
        (0 until chunkCount).forEach { i ->
            downloaded.addAndGet(File(chunkDir, "part_$i").length())
        }
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, downloaded.get(), total)

        val lastUpdate = AtomicLong(downloaded.get())
        val speedRecorder = SpeedRecorder()

        // ---------- 渐进并发 worker 池 ----------
        // 先用 RAMP_START 个连接，每完成一片 +1，直到 min(threadCount, MAX_WORKERS)。
        // 避免瞬间打出 N 个连接触发 CDN 降级（这是「像单线程」的根因之一）。
        val results = arrayOfNulls<ChunkResult?>(chunkCount)
        val nextIdx = AtomicInteger(0)
        val fallback = AtomicBoolean(false)              // 任一分片检测到「服务器忽略 Range」→ 整任务回退单流
        val failReason = java.util.concurrent.atomic.AtomicReference<String?>(null)

        // ★ 固定容量信号量：容量 = effectiveWorkers，绝不手动 release，杜绝溢出崩溃
        val sem = Semaphore(effectiveWorkers)

        val allOk = coroutineScope {
            val workers = List(min(effectiveWorkers, chunkCount)) {
                async(Dispatchers.IO) {
                    while (true) {
                        if (fallback.get()) break
                        val i = nextIdx.getAndIncrement()
                        if (i >= chunkCount) break
                        // 错峰建连：首请求前按序号微延迟，平摊 TCP/TLS 突发（仅影响首请求，不影响稳态并发）
                        if (i > 0) delay(min(i.toLong(), STAGGER_CAP.toLong()) * STAGGER_MS)
                        sem.withPermit {
                            if (fallback.get()) return@withPermit
                            val start = i * chunkSize
                            val end = min(start + chunkSize - 1, total - 1)
                            val res = try {
                                downloader.downloadChunk(
                                    taskId = id, url = task.url, start = start, end = end,
                                    partFile = File(chunkDir, "part_$i"), headers = headers
                                ) { bytes ->
                                    speedLimiter.awaitAllow(bytes)
                                    val new = downloaded.addAndGet(bytes)
                                    if (!isTaskActive()) return@downloadChunk
                                    speedRecorder.onBytes(new)?.let { speed ->
                                        val remain = if (speed > 0) (total - new) * 1000 / speed else -1L
                                        _stats.update { it + (id to DownloadStats(speed, remain, effectiveWorkers)) }
                                    }
                                    notifyProgress(task.fileName, new, total)
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
                                failReason.compareAndSet(null, "分片 ${i + 1}/$chunkCount：${e.message ?: e.javaClass.simpleName}")
                                ChunkResult.FAILED
                            }
                            results[i] = res
                            when (res) {
                                ChunkResult.RANGE_IGNORED -> {
                                    // 触发整任务回退单流（其他 worker 会在下一轮循环退出）
                                    fallback.compareAndSet(false, true)
                                    Log.w(TAG, "runTask: id=$id 分片${i + 1} 检测到服务器忽略Range，准备回退单流")
                                }
                                ChunkResult.FAILED -> failReason.compareAndSet(null, "分片 ${i + 1}/$chunkCount 下载失败")
                                else -> {}
                            }
                        }
                    }
                }
            }
            workers.awaitAll()
            !fallback.get() && results.all { it == ChunkResult.OK }
        }

        // ---------- 三种结局 ----------
        if (fallback.get()) {
            // 服务器忽略 Range：回退单条整文件流（只下一次，不按分片重复下载整文件）
            Log.w(TAG, "runTask: id=$id 回退单流整文件下载（避免重复下载整文件）")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        if (!allOk) {
            Log.e(TAG, "runTask: id=$id 部分分片失败 reason=${failReason.get()}，串行断点续传重试")
            val retryOk = (0 until chunkCount).all { i ->
                val partFile = File(chunkDir, "part_$i")
                val start = i * chunkSize
                val end = min(start + chunkSize - 1, total - 1)
                if (partFile.length() >= (end - start + 1)) true
                else {
                    val ok = downloader.downloadChunk(
                        taskId = id, url = task.url, start = start, end = end,
                        partFile = partFile, headers = headers
                    ) { bytes ->
                        speedLimiter.awaitAllow(bytes)
                        val new = downloaded.addAndGet(bytes)
                        if (!isTaskActive()) return@downloadChunk
                        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
                        notifyProgress(task.fileName, new, total)
                    }
                    if (ok != ChunkResult.OK) failReason.compareAndSet(null, "分片 ${i + 1}/$chunkCount 重试仍失败")
                    ok == ChunkResult.OK
                }
            }
            if (retryOk) {
                Log.d(TAG, "runTask: id=$id 重试用完所有分片，开始合并")
                finishDownload(id, chunkDir, chunkParts(chunkCount, chunkDir), task.fileName, total)
                return
            }
            // 重试仍失败：回退单流
            Log.w(TAG, "runTask: id=$id 分片重试失败，回退单流整文件下载")
            singleStreamFallback(id, task, headers, total, chunkDir, failReason)
            return
        }
        Log.d(TAG, "runTask: id=$id 所有分片完成，开始合并")
        finishDownload(id, chunkDir, chunkParts(chunkCount, chunkDir), task.fileName, total)
    }

    /** 分片 part 文件列表（按序） */
    private fun chunkParts(chunkCount: Int, chunkDir: File): List<File> =
        (0 until chunkCount).map { File(chunkDir, "part_$it") }

    /**
     * 回退：单条整文件流下载（服务器忽略 Range 时）。
     * 写入**独立**的 full_single.bin（从 0 开始），不复用 part_0，避免与已下分片错位/重复。
     */
    private suspend fun singleStreamFallback(
        id: Long,
        task: DownloadTaskEntity,
        headers: Map<String, String>,
        total: Long,
        chunkDir: File,
        failReason: java.util.concurrent.atomic.AtomicReference<String?>
    ) {
        val fullFile = File(chunkDir, "full_single.bin").apply { delete() } // 全新整文件，从 0 开始
        val fullDownloaded = AtomicLong(0)
        val ok = downloader.downloadFull(id, task.url, fullFile, headers) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = fullDownloaded.addAndGet(bytes)
            if (!isTaskActive()) return@downloadFull
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, total)
            notifyProgress(task.fileName, new, total)
        }
        if (!ok) throw IllegalStateException(failReason.get() ?: "分片与单流下载均失败")
        finishDownload(id, chunkDir, listOf(fullFile), task.fileName, total)
    }

    /** 流式降级下载：总大小未知时单分片开放区间下载（Range: bytes=from-），读到 EOF */
    private suspend fun streamDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, task.downloadedSize, 0)
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val chunkDir = chunkDirOf(id).apply { mkdirs() }
        val partFile = File(chunkDir, "part_0")
        val downloaded = AtomicLong(partFile.length())
        val ok = downloader.downloadChunk(
            taskId = id,
            url = task.url,
            start = 0,
            end = Long.MAX_VALUE,
            partFile = partFile,
            headers = headers
        ) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            if (!isTaskActive()) return@downloadChunk
            // 大小未知：只更新已下载量（total=0 表示未知）
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, 0)
            // 前台通知进度（2 秒节流，total 未知时仅更新标题）
            notifyProgress(task.fileName, new, 0)
        }
        if (ok != ChunkResult.OK) {
            // Range 被 CDN 拒绝（416/403）或忽略（200 整文件）：回退为无 Range 完整 GET
            Log.w(TAG, "streamDownload: id=$id Range 失败，回退完整 GET 下载")
            val ok2 = downloader.downloadFull(
                taskId = id,
                url = task.url,
                partFile = partFile,
                headers = headers
            ) { bytes ->
                speedLimiter.awaitAllow(bytes)
                val new = downloaded.addAndGet(bytes)
                if (!isTaskActive()) return@downloadFull
                dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, 0)
            }
            if (!ok2) throw IllegalStateException("下载失败（Range 与完整下载均失败）")
        }
        if (!isTaskActive()) return
        finishDownload(id, chunkDir, listOf(partFile), task.fileName, 0)
    }

    /** HLS（m3u8 转码流，如 UC play）下载：拉取分片合并 → 保存 → 完成回调 */
    private suspend fun hlsDownload(id: Long, task: DownloadTaskEntity, headers: Map<String, String>) {
        if (!isTaskActive()) return
        _stats.update { it + (id to DownloadStats(0L, -1L, 1)) }
        val hlsFile = File(context.cacheDir, "hls_$id")
        hlsFile.delete()
        val downloaded = AtomicLong(0)
        val ok = HlsDownloader.download(task.url, headers, hlsFile) { bytes ->
            speedLimiter.awaitAllow(bytes)
            val new = downloaded.addAndGet(bytes)
            dao.updateProgress(id, DownloadTaskEntity.STATUS_DOWNLOADING, new, 0)
            notifyProgress(task.fileName, new, 0)
        }
        if (!isTaskActive()) return
        if (!ok) {
            hlsFile.delete()
            throw IllegalStateException("HLS 转码流下载失败")
        }
        // Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            hlsFile.delete()
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        val savedPath = DownloadSaver.save(context, task.fileName, hlsFile, saveDirProvider())
            ?: throw IllegalStateException("保存到下载目录失败")
        dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath)
        Log.d(TAG, "hlsDownload: id=$id 下载完成 savedPath=$savedPath size=${hlsFile.length()}")
        taskCallbacks.remove(id)?.let { cb -> runCatching { cb() } }
        _stats.update { it - id }
        hlsFile.delete()
    }

    /**
     * 合并分片 → 保存到公共 Download 目录 → 触发完成回调 → 清理。
     * ★ 增加完整性校验：分片非空 + 合并后总大小 == total，任一不符直接抛错，绝不保存损坏文件。
     */
    private suspend fun finishDownload(
        id: Long,
        chunkDir: File,
        chunkFiles: List<File>,
        fileName: String,
        total: Long
    ) {
        if (!isTaskActive()) return
        // 1) 分片完整性
        for (part in chunkFiles) {
            if (!part.exists() || part.length() <= 0) {
                Log.e(TAG, "finishDownload: id=$id 分片缺失/为空 $part")
                throw IllegalStateException("分片文件缺失或为空，拒绝合并（防止文件损坏）")
            }
        }
        // 2) 合并
        val merged = File(context.cacheDir, "merged_$id")
        if (!downloader.mergeChunks(chunkFiles, merged)) {
            Log.e(TAG, "finishDownload: id=$id 合并分片失败")
            throw IllegalStateException("合并分片失败")
        }
        // 3) 整体大小校验（total>0 时）
        if (total > 0 && merged.length() != total) {
            Log.e(TAG, "finishDownload: id=$id 文件大小校验失败 期望=$total 实际=${merged.length()}")
            merged.delete()
            throw IllegalStateException("文件大小校验失败：期望 $total 字节，实际 ${merged.length()} 字节（已拒绝保存损坏文件）")
        }
        // 4) Android 9- 保存前检查存储权限（动态申请，授权后继续；无权限则报错提示）
        if (!storagePermissionProvider()) {
            merged.delete()
            throw IllegalStateException("未授予存储权限，无法保存到下载目录")
        }
        // 5) 保存（自定义目录经 SAF 写入；默认目录走 MediaStore/传统路径）
        val savedPath = DownloadSaver.save(context, fileName, merged, saveDirProvider())
            ?: throw IllegalStateException("保存到下载目录失败")
        dao.complete(id, DownloadTaskEntity.STATUS_COMPLETED, savedPath)
        Log.d(TAG, "finishDownload: id=$id 下载完成 savedPath=$savedPath size=${merged.length()}")
        taskCallbacks.remove(id)?.let { cb ->
            runCatching { cb() }
        }
        _stats.update { it - id }
        merged.delete()
        chunkDir.deleteRecursively()
    }

    /** 速度采样器：每 500ms 计算一次平均速度 */
    private class SpeedRecorder {
        private var lastBytes = 0L
        private var lastTime = System.currentTimeMillis()

        @Synchronized
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

    /** 全局限速器（令牌桶）：所有任务合计不超过 speedLimitProvider 的字节/秒；0 = 不限速 */
    private inner class SpeedLimiter {
        @Volatile
        private var tokens = 0L
        @Volatile
        private var lastRefillNanos = System.nanoTime()

        @Synchronized
        private fun refill(limit: Long) {
            val now = System.nanoTime()
            val elapsedSec = ((now - lastRefillNanos).coerceAtLeast(0) / 1_000_000_000.0)
            lastRefillNanos = now
            tokens = minOf(limit, tokens + (elapsedSec * limit).toLong())
        }

        /** 消耗 bytes 字节额度；不足则挂起等待（限速生效） */
        suspend fun awaitAllow(bytes: Long) {
            val limit = speedLimitProvider().coerceAtLeast(0L)
            if (limit <= 0L) return
            while (true) {
                val waitMs = synchronized(this) {
                    refill(limit)
                    if (bytes <= tokens) {
                        tokens -= bytes
                        return
                    }
                    ((bytes - tokens) * 1000 / limit).coerceIn(1L, 200L)
                }
                // 锁外挂起等待，避免持锁阻塞其他任务
                delay(waitMs)
            }
        }
    }

    private fun chunkDirOf(id: Long): File =
        File(context.filesDir, "download_tmp/$id")

    /** 分片数规划：分片数 ≥ 并发线程数（避免线程饿死），且单分片不小于 1MB，封顶 64 */
    private fun chunkCountFor(total: Long, threads: Int): Int {
        if (total <= 0) return 1
        val minChunkBytes = 1 * 1024 * 1024L
        val bySize = when {
            total < 5 * 1024 * 1024 -> 1          // < 5MB
            total < 50 * 1024 * 1024 -> 4         // < 50MB
            total < 500 * 1024 * 1024 -> 8        // < 500MB
            else -> 16                            // ≥ 500MB 基础值
        }
        // 分片数至少喂饱所有并发线程，但不超过 total/minChunkBytes 与 64 封顶
        val want = maxOf(bySize, threads)
        return minOf(want, (total / minChunkBytes).toInt().coerceAtLeast(1), 64)
    }
}