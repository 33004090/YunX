package com.yunx.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.screens.DriveScreen
import com.yunx.app.ui.screens.ResolveScreen
import com.yunx.app.ui.screens.SettingsScreen

/**
 * 主页框架：
 * - 顶部可折叠大标题（LargeTopAppBar），切换 Tab 时标题文字随 Tab 变化，折叠状态不受影响；
 * - 底部 4 个导航 Tab（解析 / 网盘 / 下载 / 设置）；
 * - 通过 SaveableStateHolder 保存各页面状态，切换 Tab 再切回来不会重置。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Resolve) }
    val saveableStateHolder = rememberSaveableStateHolder()

    // 折叠标题状态提升到本层：跨页面共享，页面切换时折叠/展开状态保持不变
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            LargeTopAppBar(
                title = {
                    Text(
                        text = currentTab.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            NavigationBar {
                MainTab.values().forEach { tab ->
                    NavigationBarItem(
                        selected = currentTab == tab,
                        onClick = { currentTab = tab },
                        icon = {
                            Icon(
                                imageVector = if (currentTab == tab) tab.selectedIcon else tab.unselectedIcon,
                                contentDescription = tab.title
                            )
                        },
                        label = { Text(tab.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 每个页面独立保存状态，切换 Tab 再切回来不丢失
            saveableStateHolder.SaveableStateProvider(currentTab) {
                when (currentTab) {
                    MainTab.Resolve -> ResolveScreen(scrollBehavior)
                    MainTab.Drive -> DriveScreen(scrollBehavior)
                    MainTab.Download -> DownloadScreen(scrollBehavior)
                    MainTab.Settings -> SettingsScreen(scrollBehavior)
                }
            }
        }
    }
}
