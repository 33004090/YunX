package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.BaiduConstants
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 百度网盘云盘浏览 UI 状态 */
sealed interface BaiduCloudUiState {
    data object Loading : BaiduCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        /** 当前目录绝对路径（根="/"） */
        val dirPath: String
    ) : BaiduCloudUiState
    data class Error(val message: String) : BaiduCloudUiState
}

/**
 * 百度网盘云盘浏览 ViewModel（参考夸克/UC/迅雷云盘）：
 * - 目录浏览（根/子目录/面包屑回退）+ 下拉刷新
 * - 文件操作：下载 / 重命名 / 移动 / 创建分享 / 删除 + 长按多选批量
 * 认证走 Cookie（BDUSS），目录用绝对路径，文件标识 fs_id + path。
 */
class BaiduCloudViewModel(
    private val api: BaiduApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<BaiduCloudUiState>(BaiduCloudUiState.Loading)
    val uiState: StateFlow<BaiduCloudUiState> = _uiState.asStateFlow()

    var actionFile by mutableStateOf<ShareFile?>(null)
        private set
    var cloudMessage by mutableStateOf<String?>(null)
        private set
    var isOperating by mutableStateOf(false)
        private set
    var refreshing by mutableStateOf(false)
        private set
    var downloadTriggered by mutableStateOf(0)
        private set
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set
    var multiSelectMode by mutableStateOf(false)
        private set
    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    private val dirStack = ArrayDeque<String>()
    private val nameStack = ArrayDeque<String>()

    private val _moveUiState = MutableStateFlow<BaiduCloudUiState>(BaiduCloudUiState.Loading)
    val moveUiState: StateFlow<BaiduCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    init {
        loadRoot()
    }

    private suspend fun cookie(): String =
        cookieProvider() ?: throw IllegalStateException("请先登录百度网盘")

    // ---------- 目录浏览 ----------

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("/", emptyList())
    }

    fun openFolder(file: ShareFile) {
        val path = file.fidToken
        dirStack.addLast(path)
        nameStack.addLast(file.fname)
        load(path, nameStack.toList())
    }

    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
    }

    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "/", nameStack.toList())
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
        moveLoad("/", emptyList())
    }

    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fidToken)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fidToken, moveNameStack.toList())
    }

    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "/", moveNameStack.toList())
    }

    private fun moveLoad(dirPath: String, pathNames: List<String>) {
        _moveUiState.value = BaiduCloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirPath, cookie()) ?: emptyList()
                _moveUiState.value = BaiduCloudUiState.Loaded(files, pathNames, dirPath)
            } catch (e: Exception) {
                _moveUiState.value = BaiduCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    // ---------- 单文件操作 ----------

    /** 下载：locatedownload 取直链（需 Cookie + netdisk UA）→ 内置下载队列 */
    fun downloadFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val link = api.locateDownload(file.fidToken, cookie())
                downloadManager.enqueue(
                    url = link,
                    fileName = file.fname,
                    size = file.fsize,
                    headers = mapOf(
                        "Cookie" to cookie(),
                        "User-Agent" to BaiduConstants.UA_NETDISK
                    )
                )
                cloudMessage = "已加入下载：${file.fname}"
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
                if (api.renameFile(file.fidToken, newName, cookie())) {
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

    /** 移动 */
    fun moveFile(toDirPath: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.moveFiles(listOf(file.fidToken), toDirPath, cookie())
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

    /** 创建分享（百度必须带 4 位提取码） */
    fun shareFile(period: Int, pwd: String) {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val result = api.createShare(listOf(file.fid), period, pwd, cookie())
                shareResult = ShareInfo(
                    shareUrl = result.link,
                    passcode = result.pwd,
                    pwdId = result.shareId,
                    title = file.fname,
                    expiredType = expireType(period)
                )
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 删除 */
    fun deleteFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                api.deleteFiles(listOf(file.fidToken), cookie())
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

    // ---------- 批量操作 ----------

    /** 批量下载（不切页） */
    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                var okCount = 0
                files.forEach { file ->
                    runCatching {
                        val link = api.locateDownload(file.fidToken, cookie())
                        downloadManager.enqueue(
                            url = link,
                            fileName = file.fname,
                            size = file.fsize,
                            headers = mapOf(
                                "Cookie" to cookie(),
                                "User-Agent" to BaiduConstants.UA_NETDISK
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
    fun shareSelected(period: Int, pwd: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val result = api.createShare(
                    files.map { it.fid }, period, pwd, cookie()
                )
                shareResult = ShareInfo(
                    shareUrl = result.link,
                    passcode = result.pwd,
                    pwdId = result.shareId,
                    title = if (files.size == 1) files[0].fname else "分享 ${files.size} 个文件",
                    expiredType = expireType(period)
                )
                exitMultiSelect()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    /** 批量移动 */
    fun moveSelected(toDirPath: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                api.moveFiles(files.map { it.fidToken }, toDirPath, cookie())
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

    /** 批量删除 */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                api.deleteFiles(files.map { it.fidToken }, cookie())
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

    /** 下拉刷新 */
    fun refresh() {
        val current = uiState.value
        if (current !is BaiduCloudUiState.Loaded) {
            loadRoot()
            return
        }
        refreshing = true
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(current.dirPath, cookie())
                _uiState.value = BaiduCloudUiState.Loaded(files, current.pathNames, current.dirPath)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is BaiduCloudUiState.Loaded) {
            load(current.dirPath, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirPath: String, pathNames: List<String>) {
        _uiState.value = BaiduCloudUiState.Loading
        viewModelScope.launch {
            try {
                val files = api.listCloudFiles(dirPath, cookie())
                _uiState.value = BaiduCloudUiState.Loaded(files, pathNames, dirPath)
            } catch (e: Exception) {
                _uiState.value = BaiduCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    /** 百度 period → ShareInfo.expiredType（0永久/1一天/7七天/30三十天 → 1/2/3/4） */
    private fun expireType(period: Int): Int = when (period) {
        1 -> 2
        7 -> 3
        30 -> 4
        else -> 1
    }

    class Factory(
        private val api: BaiduApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            BaiduCloudViewModel(api, cookieProvider, downloadManager) as T
    }
}