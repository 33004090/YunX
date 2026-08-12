package com.yunx.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 全局 Snackbar 通道：任何位置（Composable / 工具函数）调用 show() 即可显示。
 * 页面层使用 GlobalSnackbarHost() 渲染监听；同一时刻只有一个页面在组合中，不会重复显示。
 */
object SnackbarController {
    private val _events = MutableStateFlow<String?>(null)
    val events: StateFlow<String?> = _events

    fun show(message: String) {
        _events.value = message
    }

    fun consume() {
        _events.value = null
    }
}

/** 渲染 SnackbarHost 并监听全局事件（放在页面最外层 Box 内即可，位于内容之上、不拦截点击） */
@Composable
fun GlobalSnackbarHost(modifier: Modifier = Modifier) {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        SnackbarController.events.collect { msg ->
            if (msg != null) {
                hostState.showSnackbar(msg)
                SnackbarController.consume()
            }
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        SnackbarHost(hostState = hostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 供 Scaffold(snackbarHost = { SnackbarHost(state) }) 或页面 Box 使用的宿主状态（自动监听全局事件） */
@Composable
fun rememberGlobalSnackbarHostState(): SnackbarHostState {
    val hostState = remember { SnackbarHostState() }
    LaunchedEffect(hostState) {
        SnackbarController.events.collect { msg ->
            if (msg != null) {
                hostState.showSnackbar(msg)
                SnackbarController.consume()
            }
        }
    }
    return hostState
}
