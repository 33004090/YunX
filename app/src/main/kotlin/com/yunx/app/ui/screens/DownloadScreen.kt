package com.yunx.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yunx.app.ui.components.PlaceholderScreen

/**
 * 下载页：暂为占位，后续展示下载任务列表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        icon = Icons.Outlined.Download,
        title = "下载中心",
        description = "解析后的文件将在这里排队下载",
        scrollBehavior = scrollBehavior,
        modifier = modifier
    )
}
