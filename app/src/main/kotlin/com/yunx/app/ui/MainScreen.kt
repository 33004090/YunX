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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yunx.app.data.db.AppDatabase
import com.yunx.app.data.download.ChunkDownloader
import com.yunx.app.data.download.DownloadManager
import com.yunx.app.data.network.BaiduApi
import com.yunx.app.data.network.C139Api
import com.yunx.app.data.network.QuarkApi
import com.yunx.app.data.network.UCApi
import com.yunx.app.data.network.XunleiApi
import com.yunx.app.data.prefs.SettingsRepository
import com.yunx.app.data.repository.BaiduAccountRepository
import com.yunx.app.data.repository.BaiduResolveRepository
import com.yunx.app.data.repository.C139AccountRepository
import com.yunx.app.data.repository.C139ResolveRepository
import com.yunx.app.data.repository.QuarkAccountRepository
import com.yunx.app.data.repository.QuarkResolveRepository
import com.yunx.app.data.repository.UCAccountRepository
import com.yunx.app.data.repository.UCResolveRepository
import com.yunx.app.data.repository.XunleiAccountRepository
import com.yunx.app.data.repository.XunleiResolveRepository
import com.yunx.app.ui.login.BaiduLoginScreen
import com.yunx.app.ui.login.C139LoginScreen
import com.yunx.app.ui.login.QuarkLoginScreen
import com.yunx.app.ui.login.UCLoginScreen
import com.yunx.app.ui.login.XunleiLoginScreen
import com.yunx.app.ui.navigation.MainTab
import com.yunx.app.ui.screens.AboutScreen
import com.yunx.app.ui.screens.DownloadScreen
import com.yunx.app.ui.screens.DriveScreen
import com.yunx.app.ui.screens.OnboardingScreen
import com.yunx.app.ui.screens.ResolveScreen
import com.yunx.app.ui.screens.SettingsScreen
import com.yunx.app.ui.viewmodel.BaiduAccountViewModel
import com.yunx.app.ui.viewmodel.C139AccountViewModel
import com.yunx.app.ui.viewmodel.DownloadViewModel
import com.yunx.app.ui.viewmodel.QuarkAccountViewModel
import com.yunx.app.ui.viewmodel.ResolveViewModel
import com.yunx.app.ui.viewmodel.UCAccountViewModel
import com.yunx.app.ui.viewmodel.XunleiAccountViewModel
import okhttp3.OkHttpClient

/**
 * 主页框架：
 * - 顶部可折叠大标题（LargeTopAppBar），切换 Tab 时标题文字随 Tab 变化，折叠状态不受影响；
 * - 底部 4 个导航 Tab（解析 / 网盘 / 下载 / 设置）；
 * - 通过 SaveableStateHolder 保存各页面状态，切换 Tab 再切回来不会重置；
 * - 夸克登录页全屏覆盖展示。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var currentTab by rememberSaveable { mutableStateOf(MainTab.Resolve) }
    var showQuarkLogin by rememberSaveable { mutableStateOf(false) }
    var showUCLogin by rememberSaveable { mutableStateOf(false) }
    var showXunleiLogin by rememberSaveable { mutableStateOf(false) }
    var showBaiduLogin by rememberSaveable { mutableStateOf(false) }
    var showC139Login by rememberSaveable { mutableStateOf(false) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    val saveableStateHolder = rememberSaveableStateHolder()

    val context = LocalContext.current
    // 首次启动引导页（context 声明后检测）
    var showOnboarding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
        showOnboarding = !prefs.getBoolean("onboarding_shown", false)
    }
    val api = remember { QuarkApi() }
    val ucApi = remember { UCApi() }
    val xunleiApi = remember { XunleiApi() }
    val baiduApi = remember { BaiduApi() }
    val db = remember { AppDatabase.get(context) }
    val settings = remember { SettingsRepository(context) }
    val repository = remember {
        QuarkAccountRepository(db.quarkAccountDao(), api)
    }
    val ucRepository = remember {
        UCAccountRepository(db.ucAccountDao(), ucApi)
    }
    val xunleiRepository = remember {
        XunleiAccountRepository(db.xunleiAccountDao(), xunleiApi)
    }
    val baiduRepository = remember {
        BaiduAccountRepository(db.baiduAccountDao(), baiduApi)
    }
    val c139Repository = remember {
        C139AccountRepository(db.c139AccountDao())
    }
    // 下载管理器：OkHttp 分片下载器 + Room 任务持久化 + 可配置线程数（设置页动态生效）
    val downloadManager = remember {
        DownloadManager(
            context = context,
            dao = db.downloadTaskDao(),
            downloader = ChunkDownloader(OkHttpClient()),
            threadProvider = settings::downloadThreads
        )
    }
    val viewModel: QuarkAccountViewModel = viewModel(
        factory = QuarkAccountViewModel.Factory(repository)
    )
    val ucViewModel: UCAccountViewModel = viewModel(
        factory = UCAccountViewModel.Factory(ucRepository)
    )
    val xunleiViewModel: XunleiAccountViewModel = viewModel(
        factory = XunleiAccountViewModel.Factory(xunleiRepository)
    )
    val baiduViewModel: BaiduAccountViewModel = viewModel(
        factory = BaiduAccountViewModel.Factory(baiduRepository)
    )
    val c139ViewModel: C139AccountViewModel = viewModel(
        factory = C139AccountViewModel.Factory(c139Repository)
    )
    val xunleiResolveRepository = remember {
        XunleiResolveRepository(
            api = xunleiApi,
            accountProvider = { xunleiRepository.getAccount()?.accessToken },
            deviceIdProvider = { xunleiRepository.getAccount()?.deviceId },
            captchaProvider = { xunleiRepository.getAccount()?.captchaToken }
        )
    }
    val baiduResolveRepository = remember {
        BaiduResolveRepository(baiduApi)
    }
    val c139Api = remember { C139Api() }
    val c139ResolveRepository = remember {
        C139ResolveRepository(c139Api)
    }
    val resolveViewModel: ResolveViewModel = viewModel(
        factory = ResolveViewModel.Factory(
            repository,
            QuarkResolveRepository(api),
            ucRepository,
            UCResolveRepository(ucApi),
            xunleiRepository,
            xunleiResolveRepository,
            baiduRepository,
            baiduResolveRepository,
            c139Repository,
            c139ResolveRepository,
            downloadManager
        )
    )
    val downloadViewModel: DownloadViewModel = viewModel(
        factory = DownloadViewModel.Factory(downloadManager)
    )
    val quarkAccount by viewModel.quarkAccount.collectAsState()
    val ucAccount by ucViewModel.ucAccount.collectAsState()
    val xunleiAccount by xunleiViewModel.xunleiAccount.collectAsState()
    val baiduAccount by baiduViewModel.baiduAccount.collectAsState()
    val c139Account by c139ViewModel.c139Account.collectAsState()

    // 解析页发起下载后，自动切换到「下载」Tab
    LaunchedEffect(resolveViewModel.downloadStarted) {
        if (resolveViewModel.downloadStarted) {
            currentTab = MainTab.Download
            resolveViewModel.consumeDownloadStarted()
        }
    }

    // 首次启动引导页：全屏覆盖（优先级最高）
    if (showOnboarding) {
        OnboardingScreen(
            onFinish = {
                context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("onboarding_shown", true)
                    .apply()
                showOnboarding = false
            }
        )
        return
    }

    // 夸克登录页：全屏覆盖
    if (showQuarkLogin) {
        QuarkLoginScreen(
            viewModel = viewModel,
            onBack = { showQuarkLogin = false },
            onSaved = { showQuarkLogin = false }
        )
        return
    }

    // UC 登录页：全屏覆盖
    if (showUCLogin) {
        UCLoginScreen(
            viewModel = ucViewModel,
            onBack = { showUCLogin = false },
            onSaved = { showUCLogin = false }
        )
        return
    }

    // 迅雷登录页：全屏覆盖（账号+密码，可能触发短信验证）
    if (showXunleiLogin) {
        XunleiLoginScreen(
            viewModel = xunleiViewModel,
            onBack = { showXunleiLogin = false },
            onSaved = { showXunleiLogin = false }
        )
        return
    }

    // 百度登录页：全屏覆盖（WebView 登录提取 Cookie）
    if (showBaiduLogin) {
        BaiduLoginScreen(
            viewModel = baiduViewModel,
            onBack = { showBaiduLogin = false },
            onSaved = { showBaiduLogin = false }
        )
        return
    }

    // 139 登录页：全屏覆盖（WebView 登录提取 Cookie）
    if (showC139Login) {
        C139LoginScreen(
            viewModel = c139ViewModel,
            onBack = { showC139Login = false },
            onSaved = { showC139Login = false }
        )
        return
    }

    // 关于云析：全屏覆盖
    if (showAbout) {
        AboutScreen(
            onBack = { showAbout = false },
            onPreviewOnboarding = {
                // 重置引导标记并重新展示欢迎界面
                context.getSharedPreferences("yunx_prefs", android.content.Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("onboarding_shown", false)
                    .apply()
                showAbout = false
                showOnboarding = true
            }
        )
        return
    }

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
                    MainTab.Resolve -> ResolveScreen(scrollBehavior, resolveViewModel)
                    MainTab.Drive -> DriveScreen(
                        scrollBehavior = scrollBehavior,
                        quarkAccount = quarkAccount,
                        ucAccount = ucAccount,
                        xunleiAccount = xunleiAccount,
                        baiduAccount = baiduAccount,
                        c139Account = c139Account,
                        onQuarkLogin = { showQuarkLogin = true },
                        onQuarkLogout = { viewModel.logout() },
                        onUCLogin = { showUCLogin = true },
                        onUCLogout = { ucViewModel.logout() },
                        onXunleiLogin = { showXunleiLogin = true },
                        onXunleiLogout = { xunleiViewModel.logout() },
                        onBaiduLogin = { showBaiduLogin = true },
                        onBaiduLogout = { baiduViewModel.logout() },
                        onC139Login = { showC139Login = true },
                        onC139Logout = { c139ViewModel.logout() }
                    )
                    MainTab.Download -> DownloadScreen(scrollBehavior, downloadViewModel)
                    MainTab.Settings -> SettingsScreen(
                        scrollBehavior = scrollBehavior,
                        downloadThreads = settings.downloadThreads,
                        onThreadsChange = { settings.downloadThreads = it },
                        onAboutClick = { showAbout = true }
                    )
                }
            }
        }
    }
}