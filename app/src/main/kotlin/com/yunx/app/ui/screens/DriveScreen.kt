package com.yunx.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 网盘账号模型。
 * TODO: 后续接入 cookie 登录后，isLoggedIn 由真实登录态驱动。
 */
private data class DriveAccount(
    val id: String,
    val name: String,
    val description: String,
    val avatarText: String,
    val isLoggedIn: Boolean = false
)

/**
 * 网盘页：展示各网盘账号及其登录状态（先完成布局框架）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    val accounts = remember {
        listOf(
            DriveAccount(id = "quark", name = "夸克网盘", description = "主流分享链接解析下载", avatarText = "夸"),
            DriveAccount(id = "xunlei", name = "迅雷网盘", description = "迅雷云盘高速下载", avatarText = "迅"),
            DriveAccount(id = "uc", name = "UC网盘", description = "UC 网盘存储服务", avatarText = "UC")
        )
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "登录后即可自动携带凭证解析与下载",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        items(accounts, key = { it.id }) { account ->
            DriveAccountCard(account = account)
        }
    }
}

@Composable
private fun DriveAccountCard(account: DriveAccount) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 品牌头像（暂用首字母，后续可替换为品牌图标）
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (account.isLoggedIn) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHighest
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = account.avatarText,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = account.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = account.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            LoginBadge(isLoggedIn = account.isLoggedIn)
        }
    }
}

@Composable
private fun LoginBadge(isLoggedIn: Boolean) {
    val (label, color) = if (isLoggedIn) {
        "已登录" to MaterialTheme.colorScheme.primary
    } else {
        "未登录" to MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color = color, shape = CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
