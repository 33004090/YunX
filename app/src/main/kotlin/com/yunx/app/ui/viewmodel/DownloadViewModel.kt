package com.yunx.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yunx.app.data.db.DownloadTaskEntity
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.download.DownloadStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 下载页 ViewModel：任务列表（Room Flow → StateFlow）+ 实时统计 + 操作转发。
 */
class DownloadViewModel(private val manager: DownloadManager) : ViewModel() {

    val tasks: StateFlow<List<DownloadTaskEntity>> = manager.tasks
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    /** 实时下载统计：任务 id → 速度/剩余时间/线程数 */
    val stats: StateFlow<Map<Long, DownloadStats>> = manager.stats

    /** 添加下载任务（headers 可携带 Referer/Cookie 等） */
    fun enqueue(url: String, fileName: String, headers: Map<String, String> = emptyMap()) {
        viewModelScope.launch { manager.enqueue(url, fileName, headers) }
    }

    fun pause(id: Long) = manager.pause(id)

    fun resume(id: Long) = manager.start(id)

    fun remove(id: Long) = manager.remove(id)

    class Factory(private val manager: DownloadManager) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(DownloadViewModel::class.java))
            return DownloadViewModel(manager) as T
        }
    }
}