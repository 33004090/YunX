package com.yunx.app.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.yunx.app.ui.components.PlaceholderScreen

/**
 * 设置页：暂为占位，后续补充设置项。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    scrollBehavior: TopAppBarScrollBehavior,
    modifier: Modifier = Modifier
) {
    PlaceholderScreen(
        icon = Icons.Outlined.Settings,
        title = "设置",
        description = "偏好与账号设置将在这里展示",
        scrollBehavior = scrollBehavior,
        modifier = modifier
    )
}
