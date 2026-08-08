package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.model.ShareFile
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
 * 夸克云盘浏览 ViewModel：加载根目录/子目录文件列表，维护目录栈与面包屑路径。
 * 只做展示（进入文件夹/返回/面包屑回退），下载后续接入。
 */
class QuarkCloudViewModel(
    private val api: QuarkApi,
    private val cookieProvider: suspend () -> String?
) : ViewModel() {

    private val _uiState = MutableStateFlow<QuarkCloudUiState>(QuarkCloudUiState.Loading)
    val uiState: StateFlow<QuarkCloudUiState> = _uiState.asStateFlow()

    /** 目录 fid 栈（不含根目录 "0"） */
    private val dirStack = ArrayDeque<String>()
    /** 目录名栈（与 dirStack 一一对应） */
    private val nameStack = ArrayDeque<String>()

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
        private val cookieProvider: suspend () -> String?
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            QuarkCloudViewModel(api, cookieProvider) as T
    }
}
