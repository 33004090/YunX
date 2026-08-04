package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.data.network.SharePlatform
import com.yunx.app.data.network.UCConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.ShareResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import kotlinx.coroutines.launch

sealed interface ResolveUiState {
    data object Idle : ResolveUiState
    data object Loading : ResolveUiState
    data class Detail(val session: ShareSession, val files: List<ShareFile>) : ResolveUiState
    data class Error(val message: String) : ResolveUiState
}

/**
 * 解析页 ViewModel：分享解析状态机 + 目录导航 + 下载直链。
 */
class ResolveViewModel(
    private val accountRepository: QuarkAccountRepository,
    private val resolveRepository: QuarkResolveRepository,
    private val ucAccountRepository: UCAccountRepository,
    private val ucResolveRepository: UCResolveRepository,
    private val downloadManager: DownloadManager
) : ViewModel() {

    var uiState by mutableStateOf<ResolveUiState>(ResolveUiState.Idle)
        private set

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    var downloadError by mutableStateOf<String?>(null)
        private set

    /** 下载已入队事件：触发后由 UI 切换到下载页 */
    var downloadStarted by mutableStateOf(false)
        private set

    fun consumeDownloadStarted() {
        downloadStarted = false
    }

    fun consumeDownloadError() {
        downloadError = null
    }

    private var session: ShareSession? = null
    private var currentDirFid = QuarkConstants.DEFAULT_PDIR_FID
    private val dirStack = ArrayDeque<String>()

    /** 当前目录路径名栈（用于面包屑显示），如 [辅助工具, 专用模组] */
    var pathNames by mutableStateOf<List<String>>(emptyList())
        private set

    /** 当前解析平台（QUARK / UC），由链接自动检测 */
    private var currentPlatform: SharePlatform = SharePlatform.QUARK

    /** 开始解析：链接 → token →（密码）→ 根目录列表 */
    fun startResolve(link: String, pwd: String?) {
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            // 检测平台
            val parsed = ShareLinkParser.parse(link)
            if (parsed == null) {
                uiState = ResolveUiState.Error("无法识别分享链接")
                return@launch
            }
            currentPlatform = parsed.platform
            val isUC = parsed.platform == SharePlatform.UC
            val cookie = if (isUC) {
                ucAccountRepository.getAccount()?.cookie
            } else {
                accountRepository.getAccount()?.cookie
            }
            if (cookie.isNullOrBlank()) {
                uiState = ResolveUiState.Error(
                    if (isUC) "请先在「网盘」页登录 UC 网盘"
                    else "请先在「网盘」页登录夸克网盘"
                )
                return@launch
            }
            val repo: ShareResolveRepository = if (isUC) ucResolveRepository else resolveRepository
            repo.createSession(link, pwd, cookie)
                .onSuccess { s ->
                    session = s
                    currentDirFid = if (isUC) UCConstants.DEFAULT_PDIR_FID else QuarkConstants.DEFAULT_PDIR_FID
                    dirStack.clear()
                    pathNames = emptyList()
                    loadFiles(s, currentDirFid, cookie, repo)
                }
                .onFailure { e ->
                    uiState = ResolveUiState.Error(e.message ?: "解析失败")
                }
        }
    }

    /** 进入文件夹 */
    fun openFolder(file: ShareFile) {
        val s = session ?: return
        dirStack.addLast(currentDirFid)
        pathNames = pathNames + file.fname
        currentDirFid = file.fid
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val isUC = currentPlatform == SharePlatform.UC
            val cookie = if (isUC) ucAccountRepository.getAccount()?.cookie
            else accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) {
                uiState = ResolveUiState.Error("登录已失效，请重新登录")
                return@launch
            }
            val repo: ShareResolveRepository = if (isUC) ucResolveRepository else resolveRepository
            loadFiles(s, file.fid, cookie, repo)
        }
    }

    /** 返回上级目录 */
    fun goBack() {
        val s = session ?: return
        if (dirStack.isEmpty()) return
        currentDirFid = dirStack.removeLast()
        pathNames = pathNames.dropLast(1)
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val isUC = currentPlatform == SharePlatform.UC
            val cookie = if (isUC) ucAccountRepository.getAccount()?.cookie
            else accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) return@launch
            val repo: ShareResolveRepository = if (isUC) ucResolveRepository else resolveRepository
            loadFiles(s, currentDirFid, cookie, repo)
        }
    }

    /** 返回：在子目录则返回上一级，在根目录则返回输入页 */
    fun navigateBack() {
        if (dirStack.isEmpty()) {
            backToInput()
        } else {
            goBack()
        }
    }

    /** 返回输入页 */
    fun backToInput() {
        session = null
        downloadLink = null
        pathNames = emptyList()
        uiState = ResolveUiState.Idle
    }

    /** 获取文件下载直链：先转存到临时目录，再取直链 */
    fun fetchDownloadLink(file: ShareFile) {
        viewModelScope.launch {
            downloadLink = null
            downloadError = null
            val s = session
            if (s == null) {
                downloadError = "请先解析分享"
                return@launch
            }
            val isUC = currentPlatform == SharePlatform.UC
            val cookie = if (isUC) ucAccountRepository.getAccount()?.cookie
            else accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) {
                downloadError = "登录已失效，请重新登录"
                return@launch
            }
            val repo: ShareResolveRepository = if (isUC) ucResolveRepository else resolveRepository
            // 获取分享文件下载直链（夸克：转存后取；UC：直接取，无需转存）
            repo.getShareDownloadLink(s, file, cookie)
                .onSuccess { downloadLink = it }
                .onFailure { downloadError = it.message ?: "获取下载链接失败" }
        }
    }

    fun dismissDownloadDialog() {
        downloadLink = null
    }

    /** 将直链加入下载队列（分片多线程下载，携带 Cookie 与 UA） */
    fun startDownload(link: DownloadLink) {
        viewModelScope.launch {
            val isUC = currentPlatform == SharePlatform.UC
            val cookie = if (isUC) ucAccountRepository.getAccount()?.cookie
            else accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) {
                downloadError = "请先登录网盘"
                return@launch
            }
            val ua = if (isUC) UCConstants.USER_AGENT else QuarkConstants.API_USER_AGENT
            downloadManager.enqueue(
                url = link.downloadUrl,
                fileName = link.filename,
                headers = mapOf(
                    "Cookie" to cookie,
                    "User-Agent" to ua
                )
            )
            downloadStarted = true
        }
    }

    private suspend fun loadFiles(
        s: ShareSession,
        dirFid: String,
        cookie: String,
        repo: ShareResolveRepository
    ) {
        repo.listFiles(s, dirFid, cookie)
            .onSuccess { files ->
                uiState = ResolveUiState.Detail(s, files)
            }
            .onFailure { e ->
                uiState = ResolveUiState.Error(e.message ?: "获取文件列表失败")
            }
    }

    class Factory(
        private val accountRepository: QuarkAccountRepository,
        private val resolveRepository: QuarkResolveRepository,
        private val ucAccountRepository: UCAccountRepository,
        private val ucResolveRepository: UCResolveRepository,
        private val downloadManager: DownloadManager
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ResolveViewModel::class.java))
            return ResolveViewModel(accountRepository, resolveRepository, ucAccountRepository, ucResolveRepository, downloadManager) as T
        }
    }
}