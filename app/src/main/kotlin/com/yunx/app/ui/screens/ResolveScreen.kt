package com.yunx.app.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yunx.app.data.network.ShareLinkParser
import com.yunx.app.ui.resolve.DownloadLinkDialog
import com.yunx.app.ui.resolve.ShareDetailScreen
import com.yunx.app.ui.viewmodel.ResolveUiState
import com.yunx.app.ui.viewmodel.ResolveViewModel

/**
 * 解析页：输入分享链接与提取码 → 解析 → 展示分享详情 → 获取下载直链。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: ResolveViewModel,
    modifier: Modifier = Modifier
) {
    val state = viewModel.uiState
    val downloadLink = viewModel.downloadLink
    val downloadError = viewModel.downloadError
    val context = LocalContext.current

    // 下载错误提示
    LaunchedEffect(downloadError) {
        downloadError?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.consumeDownloadError()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (state) {
            is ResolveUiState.Detail -> ShareDetailScreen(
                session = state.session,
                files = state.files,
                viewModel = viewModel,
                scrollBehavior = scrollBehavior,
                onBack = { viewModel.navigateBack() }
            )
            is ResolveUiState.Loading -> LoadingContent()
            else -> ResolveInputContent(
                viewModel = viewModel,
                scrollBehavior = scrollBehavior,
                state = state
            )
        }
    }

    // 下载直链弹窗
    downloadLink?.let { link ->
        DownloadLinkDialog(
            link = link,
            onDismiss = { viewModel.dismissDownloadDialog() }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ResolveInputContent(
    viewModel: ResolveViewModel,
    scrollBehavior: TopAppBarScrollBehavior,
    state: ResolveUiState
) {
    var link by rememberSaveable { mutableStateOf("") }
    var pwd by rememberSaveable { mutableStateOf("") }
    var pwdEdited by rememberSaveable { mutableStateOf(false) }
    val isLoading = state is ResolveUiState.Loading

    // 链接变化时自动匹配提取码（用户未手动输入时）
    LaunchedEffect(link) {
        if (!pwdEdited) {
            ShareLinkParser.parse(link)?.pwd?.let { pwd = it }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "粘贴分享链接，一键解析分享内容",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：https://pan.quark.cn/s/xxxx") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            minLines = 3,
            maxLines = 6,
            shape = MaterialTheme.shapes.large
        )

        OutlinedTextField(
            value = pwd,
            onValueChange = {
                pwd = it
                pwdEdited = true
            },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("提取码（可选）") },
            placeholder = { Text("自动识别或手动输入") },
            singleLine = true,
            shape = MaterialTheme.shapes.large
        )

        Button(
            onClick = { viewModel.startResolve(link, pwd) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = link.isNotBlank() && !isLoading
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("解析中…")
            } else {
                Text("开始解析")
            }
        }

        if (state is ResolveUiState.Error) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ErrorOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}

/** 全屏加载中（进入文件夹/解析中展示，避免闪回输入页） */
@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "加载中…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}