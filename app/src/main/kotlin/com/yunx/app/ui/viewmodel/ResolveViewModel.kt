package com.yunx.app.ui.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.network.QuarkConstants
import com.yunx.app.data.network.model.DownloadLink
import com.yunx.app.data.network.model.ShareFile
import com.yunx.app.data.network.model.ShareSession
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
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
    private val resolveRepository: QuarkResolveRepository
) : ViewModel() {

    var uiState by mutableStateOf<ResolveUiState>(ResolveUiState.Idle)
        private set

    var downloadLink by mutableStateOf<DownloadLink?>(null)
        private set

    var downloadError by mutableStateOf<String?>(null)
        private set

    fun consumeDownloadError() {
        downloadError = null
    }

    private var session: ShareSession? = null
    private var currentDirFid = QuarkConstants.DEFAULT_PDIR_FID
    private val dirStack = ArrayDeque<String>()

    /** 开始解析：链接 → token →（密码）→ 根目录列表 */
    fun startResolve(link: String, pwd: String?) {
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val cookie = accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) {
                uiState = ResolveUiState.Error("请先在「网盘」页登录夸克网盘")
                return@launch
            }
            resolveRepository.createSession(link, pwd, cookie)
                .onSuccess { s ->
                    session = s
                    currentDirFid = QuarkConstants.DEFAULT_PDIR_FID
                    dirStack.clear()
                    loadFiles(s, currentDirFid, cookie)
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
        currentDirFid = file.fid
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val cookie = accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) {
                uiState = ResolveUiState.Error("登录已失效，请重新登录")
                return@launch
            }
            loadFiles(s, file.fid, cookie)
        }
    }

    /** 返回上级目录 */
    fun goBack() {
        val s = session ?: return
        if (dirStack.isEmpty()) return
        currentDirFid = dirStack.removeLast()
        viewModelScope.launch {
            uiState = ResolveUiState.Loading
            val cookie = accountRepository.getAccount()?.cookie ?: return@launch
            loadFiles(s, currentDirFid, cookie)
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
            val cookie = accountRepository.getAccount()?.cookie
            if (cookie.isNullOrBlank()) {
                downloadError = "登录已失效，请重新登录"
                return@launch
            }
            // 1. 确保「YunX临时转存」目录存在
            resolveRepository.ensureTempDir(cookie)
                .onFailure { downloadError = it.message ?: "转存失败" }
                .onSuccess { dirFid ->
                    // 2. 转存文件到临时目录（返回转存后的新 fid）
                    resolveRepository.transferFile(s, file, dirFid, cookie)
                        .onFailure { downloadError = it.message ?: "转存失败" }
                        .onSuccess { savedFid ->
                            // 3. 用转存后的新 fid 获取下载直链
                            resolveRepository.getDownloadLink(savedFid, cookie)
                                .onSuccess { downloadLink = it }
                                .onFailure { downloadError = it.message ?: "获取下载链接失败" }
                        }
                }
        }
    }

    fun dismissDownloadDialog() {
        downloadLink = null
    }

    private suspend fun loadFiles(s: ShareSession, dirFid: String, cookie: String) {
        resolveRepository.listFiles(s, dirFid, cookie)
            .onSuccess { files ->
                uiState = ResolveUiState.Detail(s, files)
            }
            .onFailure { e ->
                uiState = ResolveUiState.Error(e.message ?: "获取文件列表失败")
            }
    }

    class Factory(
        private val accountRepository: QuarkAccountRepository,
        private val resolveRepository: QuarkResolveRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ResolveViewModel::class.java))
            return ResolveViewModel(accountRepository, resolveRepository) as T
        }
    }
}