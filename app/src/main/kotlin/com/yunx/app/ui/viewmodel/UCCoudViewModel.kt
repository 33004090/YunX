package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UC 云盘浏览 UI 状态 */
sealed interface UCCloudUiState {
    data object Loading : UCCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirFid: String
    ) : UCCloudUiState
    data class Error(val message: String) : UCCloudUiState
}

/**
 * UC 网盘云盘浏览 ViewModel（参考夸克 QuarkCloudViewModel）：
 * - 目录浏览（根/子目录/面包屑回退）
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 + 长按多选批量操作
 * 操作成功后自动刷新当前目录，结果通过 cloudMessage（Toast）反馈。
 */
class UCCoudViewModel(
    private val api: UCApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<UCCloudUiState>(UCCloudUiState.Loading)
    val uiState: StateFlow<UCCloudUiState> = _uiState.asStateFlow()

    /** 当前操作的文件（更多按钮弹出操作菜单） */
    var actionFile by mutableStateOf<ShareFile?>(null)
        private set

    /** 操作结果消息（Toast） */
    var cloudMessage by mutableStateOf<String?>(null)
        private set

    /** 操作执行中（防止重复点击） */
    var isOperating by mutableStateOf(false)
        private set

    /** 下拉刷新中 */
    var refreshing by mutableStateOf(false)
        private set

    /** 下载入队事件计数（UI 消费后切到下载页） */
    var downloadTriggered by mutableStateOf(0)
        private set

    /** 分享创建成功后的信息（弹窗展示链接+提取码） */
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set

    /** 多选模式 */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    // ---------- 移动目标目录浏览（独立状态） ----------
    private val _moveUiState = MutableStateFlow<UCCloudUiState>(UCCloudUiState.Loading)
    val moveUiState: StateFlow<UCCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
    }

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("0", emptyList())
    }

    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    // ---------- 多选 ----------

    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    fun toggleSelectAll(files: List<ShareFile>) {
        if (_selected.size == files.size) _selected.clear()
        else {
            _selected.clear()
            _selected.addAll(files)
        }
    }

    fun exitMultiSelect() {
        multiSelectMode = false
        _selected.clear()
    }

    fun openActions(file: ShareFile) {
        actionFile = file
    }

    fun dismissActions() {
        actionFile = null
    }

    fun consumeMessage() {
        cloudMessage = null
    }

    fun dismissShareResult() {
        shareResult = null
    }

    fun consumeDownloadTriggered() {
        downloadTriggered = 0
    }

    // ---------- 移动目标浏览 ----------

    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("0", emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fid, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    private fun moveLoad(dirFid: String, pathNames: List<String>) {
        _moveUiState.value = UCCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _moveUiState.value = UCCloudUiState.Error("请先登录 UC 网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _moveUiState.value = UCCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _moveUiState.value = UCCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 下载文件：取直链（带 Cookie+UA）→ 加入内置下载队列 */
    fun downloadFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                val link = api.cloudGetDownloadLink(file.fid, cookie)
                    ?: throw IllegalStateException("获取下载链接失败")
                downloadManager.enqueue(
                    url = link.downloadUrl,
                    fileName = link.filename.ifBlank { file.fname },
                    size = link.size,
                    headers = mapOf(
                        "Cookie" to cookie,
                        // UC OSS 直链：必须带官方 Referer（否则被 Callback 限速 ~100KB/s）+ Origin，与解析页 UC 分支一致
                        "User-Agent" to UCConstants.USER_AGENT,
                        "Referer" to UCConstants.DOWNLOAD_REFERER,
                        "Origin" to UCConstants.WEB_ORIGIN
                    )
                )
                cloudMessage = "已加入下载：${link.filename.ifBlank { file.fname }}"
                actionFile = null
                downloadTriggered++
            } catch (e: Exception) {
                cloudMessage = e.message ?: "下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 重命名 */
    fun renameFile(newName: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                if (api.renameFile(file.fid, newName, cookie)) {
                    cloudMessage = "已重命名"
                    actionFile = null
                    reloadCurrent()
                } else {
                    cloudMessage = "重命名失败"
                }
            } catch (e: Exception) {
                cloudMessage = e.message ?: "重命名失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 移动文件到指定目录 */
    fun moveFile(toDirFid: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                api.moveFile(file.fid, toDirFid, cookie)
                    ?: throw IllegalStateException("移动失败")
                cloudMessage = "已移动到目标目录"
                actionFile = null
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 创建分享并查询链接 */
    fun shareFile(urlType: Int, passcode: String, expiredType: Int) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                val shareId = api.createShare(
                    fidList = listOf(file.fid),
                    title = file.fname,
                    urlType = urlType,
                    passcode = passcode,
                    expiredType = expiredType,
                    cookie = cookie
                ) ?: throw IllegalStateException("创建分享失败")
                val info = api.getShareInfo(shareId, cookie)
                    ?: throw IllegalStateException("获取分享链接失败")
                shareResult = info
                // 保留 actionFile：FileActionSheet 存活才能弹出 ShareResultDialog
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作 ----------

    /** 批量下载（不切页，保持处理中弹窗） */
    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                var okCount = 0
                files.forEach { file ->
                    runCatching {
                        val link = api.cloudGetDownloadLink(file.fid, cookie) ?: return@runCatching
                        downloadManager.enqueue(
                            url = link.downloadUrl,
                            fileName = link.filename.ifBlank { file.fname },
                            size = link.size,
                            headers = mapOf(
                                "Cookie" to cookie,
                                // UC OSS 直链：必须带官方 Referer（否则被 Callback 限速 ~100KB/s）+ Origin，与解析页 UC 分支一致
                                "User-Agent" to UCConstants.USER_AGENT,
                                "Referer" to UCConstants.DOWNLOAD_REFERER,
                                "Origin" to UCConstants.WEB_ORIGIN
                            )
                        )
                        okCount++
                    }
                }
                cloudMessage = "已加入 $okCount 个下载任务"
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "批量下载失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量分享 */
    fun shareSelected(urlType: Int, passcode: String, expiredType: Int) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                val shareId = api.createShare(
                    fidList = files.map { it.fid },
                    title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件",
                    urlType = urlType,
                    passcode = passcode,
                    expiredType = expiredType,
                    cookie = cookie
                ) ?: throw IllegalStateException("创建分享失败")
                val info = api.getShareInfo(shareId, cookie)
                    ?: throw IllegalStateException("获取分享链接失败")
                shareResult = info
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量移动 */
    fun moveSelected(toDirFid: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                files.forEach { file ->
                    api.moveFile(file.fid, toDirFid, cookie)
                }
                cloudMessage = "已移动 ${files.size} 项"
                exitMultiSelect()
                kotlinx.coroutines.delay(1500)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "移动失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除文件（二次确认由 UI 层负责） */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                api.deleteFile(file.fid, cookie)
                    ?: throw IllegalStateException("删除失败")
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                kotlinx.coroutines.delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录 UC 网盘"
                    return@launch
                }
                files.forEach { file ->
                    api.deleteFile(file.fid, cookie)
                }
                cloudMessage = "已删除 ${files.size} 项"
                exitMultiSelect()
                kotlinx.coroutines.delay(1200)
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 内部 ----------

    /** 下拉刷新当前目录 */
    fun refresh() {
        val current = uiState.value
        if (current !is UCCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                refreshing = false
                return@launch
            }
            try {
                val files = api.listCloudFiles(current.dirFid, cookie) ?: emptyList()
                _uiState.value = UCCloudUiState.Loaded(files, current.pathNames, current.dirFid)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is UCCloudUiState.Loaded) {
            load(current.dirFid, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirFid: String, pathNames: List<String>) {
        _uiState.value = UCCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _uiState.value = UCCloudUiState.Error("请先登录 UC 网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _uiState.value = UCCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _uiState.value = UCCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: UCApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UCCoudViewModel(api, cookieProvider, downloadManager) as T
    }
}