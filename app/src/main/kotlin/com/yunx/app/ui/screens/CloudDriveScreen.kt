package com.yunx.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.ui.resolve.BackToParentItem
import com.yunx.app.ui.resolve.CrumbBar
import com.yunx.app.ui.resolve.ShareFileRow
import com.yunx.app.ui.viewmodel.QuarkCloudUiState
import com.yunx.app.ui.viewmodel.QuarkCloudViewModel

/**
 * 夸克云盘浏览页：展示个人网盘文件，支持进入文件夹 / 返回 / 面包屑回退。
 * 复用解析详情页的 ShareFileRow / CrumbBar / BackToParentItem 组件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudDriveScreen(
    viewModel: QuarkCloudViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    // 批量操作弹窗（多选模式底部栏触发）
    var showBatchActions by remember { mutableStateOf(false) }

    // 操作结果 Toast（放在本层：弹窗关闭后仍能正常弹出）
    LaunchedEffect(viewModel.cloudMessage) {
        viewModel.cloudMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeMessage()
        }
    }

    // 不透明背景包裹：避免 Tab 内切换时透出下层内容（账号列表）导致视觉重叠
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface
    ) {
        when (val s = state) {
            is QuarkCloudUiState.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            is QuarkCloudUiState.Error -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = s.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    TextButton(onClick = { viewModel.loadRoot() }) {
                        Text("重试")
                    }
                }
            }

            is QuarkCloudUiState.Loaded -> Box(modifier = Modifier.fillMaxSize()) {
                PullToRefreshBox(
                    isRefreshing = viewModel.refreshing,
                    onRefresh = { viewModel.refresh() },
                    modifier = Modifier.fillMaxSize()
                ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
            item {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (viewModel.multiSelectMode) {
                            // 多选模式：取消选择
                            IconButton(onClick = { viewModel.exitMultiSelect() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "取消选择")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "已选 ${viewModel.selected.size} 项",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = if (viewModel.selected.size == s.files.size) "已全选" else "点击选择更多文件",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { viewModel.toggleSelectAll(s.files) }) {
                                Text(if (viewModel.selected.size == s.files.size) "取消全选" else "全选")
                            }
                        } else {
                            IconButton(onClick = onExit) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "夸克网盘",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "共 ${s.files.size} 项",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // 可点击面包屑（多选模式下隐藏）
                    if (!viewModel.multiSelectMode) {
                        CrumbBar(
                            rootTitle = "夸克网盘",
                            pathNames = s.pathNames,
                            onNavigate = { viewModel.navigateToLevel(it) }
                        )
                    }
                }
            }

            // 返回上一级（根目录时不显示）
            if (s.pathNames.isNotEmpty()) {
                item {
                    BackToParentItem(onClick = { viewModel.back() })
                }
            }

            if (s.files.isEmpty()) {
                item {
                    Text(
                        text = "此目录为空",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            items(s.files, key = { it.fid }) { file ->
                ShareFileRow(
                    file = file,
                    onClick = {
                        if (viewModel.multiSelectMode) {
                            viewModel.toggleSelect(file)
                        } else if (file.isdir) {
                            viewModel.openFolder(file)
                        } else {
                            viewModel.openActions(file)
                        }
                    },
                    // 多选模式：隐藏行尾按钮；非多选时文件夹显示「更多」、全部可长按进入多选
                    onMore = if (!viewModel.multiSelectMode && file.isdir) {
                        { viewModel.openActions(file) }
                    } else {
                        null
                    },
                    onLongClick = if (!viewModel.multiSelectMode) {
                        { viewModel.enterMultiSelect(file) }
                    } else {
                        null
                    },
                    selected = viewModel.selected.contains(file)
                )
            }
                }
                // 多选模式：底部批量操作栏
                if (viewModel.multiSelectMode) {
                    MultiSelectBar(
                        count = viewModel.selected.size,
                        onBatch = { showBatchActions = true }
                    )
                }
            }
        }
    }
    }

    // 文件操作弹窗（更多按钮/点击文件 → 下载/分享/移动/重命名/删除）
    viewModel.actionFile?.let { file ->
        FileActionSheet(
            file = file,
            viewModel = viewModel,
            onDismiss = { viewModel.dismissActions() }
        )
    }

    // 批量操作弹窗（长按多选 → 底部栏）
    if (showBatchActions) {
        BatchActionSheet(
            viewModel = viewModel,
            onDismiss = { showBatchActions = false }
        )
    }
}

/** 多选模式底部批量操作栏 */
@Composable
private fun MultiSelectBar(
    count: Int,
    onBatch: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BatchActionIcon(
                icon = Icons.Outlined.Download,
                label = "下载",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onBatch
            )
            BatchActionIcon(
                icon = Icons.Outlined.Share,
                label = "分享",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onBatch
            )
            BatchActionIcon(
                icon = Icons.Outlined.DriveFileMove,
                label = "移动",
                tint = MaterialTheme.colorScheme.primary,
                onClick = onBatch
            )
            BatchActionIcon(
                icon = Icons.Outlined.Delete,
                label = "删除",
                tint = MaterialTheme.colorScheme.error,
                onClick = onBatch
            )
        }
        }
    }
}

/** 批量操作图标项 */
@Composable
private fun BatchActionIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}