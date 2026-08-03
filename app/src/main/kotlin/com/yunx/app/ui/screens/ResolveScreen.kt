package com.yunx.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp

/**
 * 解析页：粘贴分享链接 → 开始解析。
 * 输入内容通过 rememberSaveable 保存，切换 Tab 后不会丢失。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResolveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    var link by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .nestedScroll(scrollBehavior.nestedScrollConnection)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "粘贴分享链接，一键解析并转存网盘",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("例如：https://pan.quark.cn/s/xxxxxxxx") },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
            minLines = 3,
            maxLines = 6,
            shape = MaterialTheme.shapes.large
        )

        Button(
            onClick = { /* TODO: 接入解析逻辑 */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = link.isNotBlank()
        ) {
            Text("开始解析")
        }
    }
}
