package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 夸克云盘浏览 UI 状态 */
sealed interface QuarkCloudUiState {
    data object Loading : QuarkCloudUiState
    data class Loaded(
        val files: List<ShareFile>,
        val pathNames: List<String>,
        val dirFid: String
    ) : QuarkCloudUiState
    data class Error(val message: String) : QuarkCloudUiState
}

/**
 * 夸克云盘浏览 ViewModel：
 * - 目录浏览（根/子目录/面包屑回退）
 * - 文件操作：下载 / 重命名 / 移动 / 删除 / 创建分享
 * 操作成功后自动刷新当前目录，结果通过 cloudMessage（Toast）反馈。
 */
class QuarkCloudViewModel(
    private val api: QuarkApi,
    private val cookieProvider: suspend () -> String?,
    private val downloadManager: DownloadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<QuarkCloudUiState>(QuarkCloudUiState.Loading)
    val uiState: StateFlow<QuarkCloudUiState> = _uiState.asStateFlow()

    /** 当前操作的文件（更多按钮弹出操作菜单） */
    var actionFile by mutableStateOf<ShareFile?>(null)
        private set

    /** 操作结果消息（Toast） */
    var cloudMessage by mutableStateOf<String?>(null)
        private set

    /** 操作执行中（防止重复点击） */
    var isOperating by mutableStateOf(false)
        private set

    /** 下拉刷新中（不切换 Loading 遮罩，保持列表显示） */
    var refreshing by mutableStateOf(false)
        private set

    /** 分享创建成功后的信息（弹窗展示链接+提取码） */
    var shareResult by mutableStateOf<ShareInfo?>(null)
        private set

    /** 目录 fid 栈（不含根目录 "0"） */
    private val dirStack = ArrayDeque<String>()
    /** 目录名栈（与 dirStack 一一对应） */
    private val nameStack = ArrayDeque<String>()

    // ---------- 移动目标目录浏览（独立状态，避免影响主列表） ----------

    private val _moveUiState = MutableStateFlow<QuarkCloudUiState>(QuarkCloudUiState.Loading)
    val moveUiState: StateFlow<QuarkCloudUiState> = _moveUiState.asStateFlow()
    private val moveDirStack = ArrayDeque<String>()
    private val moveNameStack = ArrayDeque<String>()

    /** 打开移动目标浏览（回到根目录） */
    fun openMoveRoot() {
        moveDirStack.clear()
        moveNameStack.clear()
        moveLoad("0", emptyList())
    }

    /** 移动目标：进入文件夹 */
    fun openMoveFolder(file: ShareFile) {
        moveDirStack.addLast(file.fid)
        moveNameStack.addLast(file.fname)
        moveLoad(file.fid, moveNameStack.toList())
    }

    /** 移动目标：返回上一级 */
    fun moveBack() {
        if (moveNameStack.isEmpty()) return
        moveDirStack.removeLast()
        moveNameStack.removeLast()
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    /** 移动目标：面包屑回退 */
    fun moveNavigateToLevel(level: Int) {
        while (moveNameStack.size > level) {
            moveDirStack.removeLast()
            moveNameStack.removeLast()
        }
        moveLoad(moveDirStack.lastOrNull() ?: "0", moveNameStack.toList())
    }

    private fun moveLoad(dirFid: String, pathNames: List<String>) {
        _moveUiState.value = QuarkCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _moveUiState.value = QuarkCloudUiState.Error("请先登录夸克网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _moveUiState.value = QuarkCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _moveUiState.value = QuarkCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    init {
        loadRoot()
    }

    fun loadRoot() {
        dirStack.clear()
        nameStack.clear()
        load("0", emptyList())
    }

    /** 进入文件夹 */
    fun openFolder(file: ShareFile) {
        dirStack.addLast(file.fid)
        nameStack.addLast(file.fname)
        load(file.fid, nameStack.toList())
    }

    /** 返回上一级（根目录时重新加载根） */
    fun back() {
        if (nameStack.isEmpty()) {
            loadRoot()
            return
        }
        dirStack.removeLast()
        nameStack.removeLast()
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    /** 面包屑回退到第 level 层（0=根目录） */
    fun navigateToLevel(level: Int) {
        while (nameStack.size > level) {
            dirStack.removeLast()
            nameStack.removeLast()
        }
        load(dirStack.lastOrNull() ?: "0", nameStack.toList())
    }

    // ---------- 文件操作 ----------

    /** 多选模式（长按进入） */
    var multiSelectMode by mutableStateOf(false)
        private set

    private val _selected = mutableStateListOf<ShareFile>()
    val selected: List<ShareFile> get() = _selected

    /** 长按进入多选并选中该文件 */
    fun enterMultiSelect(file: ShareFile) {
        multiSelectMode = true
        _selected.clear()
        _selected.add(file)
    }

    /** 切换选中状态 */
    fun toggleSelect(file: ShareFile) {
        if (_selected.contains(file)) _selected.remove(file) else _selected.add(file)
    }

    /** 全选/取消全选当前目录 */
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

    /** 打开文件操作菜单 */
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

    /** 下载文件：取直链 → 加入内置下载队列 */
    fun downloadFile() {
        val file = actionFile ?: return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                val link = api.getDownloadLink(file.fid, cookie)
                    ?: throw IllegalStateException("获取下载链接失败")
                // 完全复用解析分享下载逻辑：CDN 节点优选（探测最快节点，失败回退原链）+ Cookie/UA 头
                val effectiveUrl = com.yunx.app.data.network.QuarkCdn.fastest(link.downloadUrl, cookie)
                downloadManager.enqueue(
                    url = effectiveUrl,
                    fileName = link.filename.ifBlank { file.fname },
                    size = link.size,
                    headers = mapOf(
                        "Cookie" to cookie,
                        "User-Agent" to com.yunx.app.data.network.QuarkConstants.API_USER_AGENT
                    )
                )
                cloudMessage = "已加入下载：${link.filename.ifBlank { file.fname }}"
                actionFile = null
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
                    cloudMessage = "请先登录夸克网盘"
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
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                api.moveFile(file.fid, toDirFid, cookie)
                    ?: throw IllegalStateException("移动失败")
                cloudMessage = "已移动到目标目录"
                actionFile = null
                // 移动是异步任务（响应 finish 但服务端可能仍在处理），延迟后刷新当前目录
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
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                api.deleteFile(file.fid, cookie)
                    ?: throw IllegalStateException("删除失败")
                cloudMessage = "已删除「${file.fname}」"
                actionFile = null
                reloadCurrent()
            } catch (e: Exception) {
                cloudMessage = e.message ?: "删除失败"
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
                    cloudMessage = "请先登录夸克网盘"
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
                // 注意：不置空 actionFile —— FileActionSheet 依赖它存活，
                // 才能在其内部弹出 ShareResultDialog（置空会导致弹窗销毁、分享结果延迟显示）
            } catch (e: Exception) {
                cloudMessage = e.message ?: "分享失败"
            } finally {
                isOperating = false
            }
        }
    }

    // ---------- 批量操作（多选） ----------

    /** 批量下载：逐个取直链加入下载队列 */
    fun downloadSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
                    return@launch
                }
                var okCount = 0
                files.forEach { file ->
                    runCatching {
                        val link = api.getDownloadLink(file.fid, cookie) ?: return@runCatching
                        val effectiveUrl = com.yunx.app.data.network.QuarkCdn.fastest(link.downloadUrl, cookie)
                        downloadManager.enqueue(
                            url = effectiveUrl,
                            fileName = link.filename.ifBlank { file.fname },
                            size = link.size,
                            headers = mapOf(
                                "Cookie" to cookie,
                                "User-Agent" to com.yunx.app.data.network.QuarkConstants.API_USER_AGENT
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

    /** 批量分享选中文件 */
    fun shareSelected(urlType: Int, passcode: String, expiredType: Int) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
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

    /** 批量移动到指定目录 */
    fun moveSelected(toDirFid: String) {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
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

    /** 批量删除（二次确认由 UI 层负责） */
    fun deleteSelected() {
        val files = _selected.toList()
        if (files.isEmpty()) return
        viewModelScope.launch {
            isOperating = true
            try {
                val cookie = cookieProvider()
                if (cookie.isNullOrBlank()) {
                    cloudMessage = "请先登录夸克网盘"
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

    /** 下拉刷新当前目录（不切 Loading 遮罩，完成后更新列表） */
    fun refresh() {
        val current = uiState.value
        if (current !is QuarkCloudUiState.Loaded) {
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
                _uiState.value = QuarkCloudUiState.Loaded(files, current.pathNames, current.dirFid)
            } catch (e: Exception) {
                cloudMessage = e.message ?: "刷新失败"
            } finally {
                refreshing = false
            }
        }
    }

    private fun reloadCurrent() {
        val current = uiState.value
        if (current is QuarkCloudUiState.Loaded) {
            load(current.dirFid, current.pathNames)
        } else {
            loadRoot()
        }
    }

    private fun load(dirFid: String, pathNames: List<String>) {
        _uiState.value = QuarkCloudUiState.Loading
        viewModelScope.launch {
            val cookie = cookieProvider()
            if (cookie.isNullOrBlank()) {
                _uiState.value = QuarkCloudUiState.Error("请先登录夸克网盘")
                return@launch
            }
            try {
                val files = api.listCloudFiles(dirFid, cookie) ?: emptyList()
                _uiState.value = QuarkCloudUiState.Loaded(files, pathNames, dirFid)
            } catch (e: Exception) {
                _uiState.value = QuarkCloudUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    class Factory(
        private val api: QuarkApi,
        private val cookieProvider: suspend () -> String?,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QuarkCloudViewModel(api, cookieProvider, downloadManager) as T
    }
}