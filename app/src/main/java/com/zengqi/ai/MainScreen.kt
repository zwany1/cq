package com.zengqi.ai

import android.app.Activity
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Velocity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zengqi.ai.common.AppForegroundTracker
import com.zengqi.ai.common.YandereModeManager
import com.zengqi.ai.domain.ServiceRegistry
import com.zengqi.ai.feature.chat.ui.screen.ChatDetailScreen
import com.zengqi.ai.feature.chat.ui.screen.ChatScreen
import com.zengqi.ai.feature.chat.ui.screen.VoiceCallScreen
import com.zengqi.ai.feature.character.ui.screen.ContactsScreen
import com.zengqi.ai.feature.character.ui.screen.CreateCharacterScreen
import com.zengqi.ai.feature.importchat.ImportChatScreen
import com.zengqi.ai.feature.memory.MemoryScreen
import com.zengqi.ai.feature.profile.*
import com.zengqi.ai.feature.recall.MemorySheet
import com.zengqi.ai.feature.recall.RecallViewModel
import com.zengqi.ai.feature.recall.ThatDaySheet
import com.zengqi.ai.feature.settings.ui.screen.*
import com.zengqi.ai.feature.update.AppUpdateManager
import com.zengqi.ai.uicommon.component.UpdateDialog
import com.zengqi.ai.uicommon.theme.ZengqiTheme
import com.zengqi.ai.uicommon.theme.ThemeViewModel

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PersonOutline
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 主界面 — NavHost + Pager + Scaffold 编排。
 *
 * 状态空间模型:
 *   S ∈ {Home, Contacts, Profile, Chat(id), ChatDetail(id), VoiceCall(id),
 *        Settings, Theme, Language,
 *        CheckUpdate, About, FrameRate, Team, Support, Memory,
 *        ContextMemory, TtsSettings, TokenUsage, WeChatSettings, WeChatBind,
 *        AgreementView, CreateCompanion, EditCompanion(id)}
 *   差分方程: S[k+1] = f(S[k], E[k])
 *   验证: 所有状态出度 ≥ 1 (popBackStack 保证)
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(mainActivity: Activity) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    val context = LocalContext.current
    val valActivity = context as ComponentActivity

    val updateManager = remember {
        (valActivity as? MainActivity)?.updateManager ?: AppUpdateManager(context)
    }
    val showUpdateDialog by updateManager.showUpdateDialog.collectAsState()
    val updateInfo by updateManager.updateInfo.collectAsState()
    val downloadProgress by updateManager.downloadProgress.collectAsState()

    fun openCompanionChat(companionId: Long) {
        LastOpenedCompanionStore.save(context, companionId)
        navController.navigate(MainRoute.Chat(companionId).route)
    }

    // 深度链接 / 冷启动恢复最近打开的单聊
    LaunchedEffect(Unit) {
        val intent = valActivity.intent
        if (intent.getBooleanExtra("open_chat", false)) {
            val companionId = intent.getLongExtra("companion_id", -1L)
            if (companionId != -1L) {
                openCompanionChat(companionId)
                intent.removeExtra("open_chat")
                intent.removeExtra("companion_id")
                return@LaunchedEffect
            }
        }

        val restoredCompanionId = LastOpenedCompanionStore.resolveInitialCompanionId(
            context,
            ServiceRegistry.getOrThrow(com.zengqi.ai.database.repository.CompanionRepository::class.java)
        )
        if (restoredCompanionId != null) {
            openCompanionChat(restoredCompanionId)
        }
    }

    val bottomNavItems = listOf(
        BottomNavItem(stringResource(R.string.nav_love), Icons.Filled.ChatBubble, Icons.Outlined.ChatBubbleOutline, "home"),
        BottomNavItem(stringResource(R.string.nav_contacts), Icons.Filled.Group, Icons.Outlined.Group, "contacts"),
        BottomNavItem(stringResource(R.string.nav_profile), Icons.Filled.Person, Icons.Outlined.PersonOutline, "profile")
    )

    val mainTabRoutes = bottomNavItems.map { it.route }
    val showBottomBar = currentRoute in mainTabRoutes

    val pagerState = rememberPagerState(pageCount = { 3 })
    val coroutineScope = rememberCoroutineScope()

    // 记住离开 tab 页面前的 pager 位置，返回时恢复（用 rememberSaveable 防止 NavHost 过渡重建丢失）
    var lastTabPage by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(currentRoute) {
        if (currentRoute !in mainTabRoutes) {
            // 离开 tab 页面（进入聊天等）—— 记住当前位置
            lastTabPage = pagerState.currentPage
            return@LaunchedEffect
        }
        // 返回 tab 页面 —— 恢复到离开前的位置，而非强制定位到首页
        val targetPage = when (currentRoute) {
            "contacts" -> 1
            "profile" -> 2
            else -> lastTabPage
        }
        if (pagerState.currentPage != targetPage) {
            pagerState.scrollToPage(targetPage)
        }
    }

    // 横向滑动冲突处理: 不阻断横向, 让 pager 和子层各自处理各自轴
    val angleNestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset = Offset.Zero
            override suspend fun onPreFling(available: Velocity): Velocity = Velocity.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity = Velocity.Zero
        }
    }

    val themeViewModel: ThemeViewModel = viewModel()
    val isDark by themeViewModel.isDarkTheme.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (showBottomBar) {
                FloatingGlassBottomNav(
                    items = bottomNavItems,
                    currentIndex = pagerState.currentPage,
                    onItemClick = { index ->
                        lastTabPage = index
                        coroutineScope.launch { pagerState.animateScrollToPage(index) }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = "home",
                enterTransition = {
                    fadeIn(animationSpec = tween(250)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { it / 4 })
                },
                exitTransition = {
                    fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { -it / 6 })
                },
                popEnterTransition = {
                    fadeIn(animationSpec = tween(250)) + slideInHorizontally(animationSpec = tween(300), initialOffsetX = { -it / 4 })
                },
                popExitTransition = {
                    fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(250), targetOffsetX = { it / 4 })
                }
            ) {
                // === 主页 Pager ===
                composable(MainRoute.Home.route) {
                    val pagerOffset by remember { derivedStateOf { pagerState.currentPageOffsetFraction } }
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 1,
                        flingBehavior = PagerDefaults.flingBehavior(state = pagerState, snapPositionalThreshold = 0.4f),
                        modifier = Modifier.fillMaxSize()
                            .pointerInput(pagerState) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var totalDragX = 0f
                                    do {
                                        val event = awaitPointerEvent(PointerEventPass.Initial)
                                        event.changes.forEach { change ->
                                            totalDragX += change.position.x - change.previousPosition.x
                                        }
                                        if (abs(totalDragX) > size.width * 0.6f) {
                                            val target = (pagerState.currentPage + if (totalDragX < 0) 1 else -1).coerceIn(0, pagerState.pageCount - 1)
                                            coroutineScope.launch { pagerState.animateScrollToPage(target) }
                                            event.changes.forEach { it.consume() }
                                        }
                                    } while (event.changes.any { it.pressed })
                                }
                            }
                            .nestedScroll(angleNestedScrollConnection)
                    ) { page ->
                        val cp = pagerState.currentPage
                        val visible = remember(page, cp, pagerOffset) {
                            when {
                                page == cp -> pagerOffset in -0.6f..0.6f
                                page == cp + 1 -> pagerOffset > 0.3f
                                page == cp - 1 -> pagerOffset < -0.3f
                                else -> false
                            }
                        }
                        AnimatedVisibility(
                            visible = visible,
                            enter = fadeIn(animationSpec = tween(350)) + scaleIn(initialScale = 0.94f, animationSpec = tween(350)),
                            exit = fadeOut(animationSpec = tween(200))
                        ) {
                            when (page) {
                                0 -> HomeScreen(
                                    onCompanionClick = { openCompanionChat(it) },
                                    onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) }
                                )
                                1 -> ContactsScreen(
                                    onCompanionClick = { openCompanionChat(it) },
                                    onAddClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                                    onEditClick = { navController.navigate(MainRoute.EditCompanion(it).route) },
                                    onImportClick = { navController.navigate(MainRoute.ImportChat(it).route) }
                                )
                                2 -> ProfileScreen(
                                    // 记忆与管理
                                    onMemoryClick = { navController.navigate(MainRoute.Memory.route) },
                                    onContextMemoryClick = { navController.navigate(MainRoute.ContextMemory.route) },
                                    // AI与外观
                                    onSettingsClick = { navController.navigate(MainRoute.Settings.route) },
                                    onThemeClick = { navController.navigate(MainRoute.Theme.route) },
                                    // 总设置
                                    onGeneralSettingsClick = { navController.navigate(MainRoute.GeneralSettings.route) },
                                    // 新增角色
                                    onCreateCompanionClick = { navController.navigate(MainRoute.CreateCompanion.route) },
                                    // 关于与支持
                                    onTeamClick = { navController.navigate(MainRoute.Team.route) },
                                    onSupportClick = { navController.navigate(MainRoute.Support.route) },
                                    onThanksClick = { navController.navigate(MainRoute.Thanks.route) },
                                    onAboutClick = { navController.navigate(MainRoute.About.route) }
                                )
                            }
                        }
                    }
                }

                // === 伴侣创建/编辑 ===
                composable(MainRoute.CreateCompanion.route) { CreateCharacterScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.EditCompanion(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    CreateCharacterScreen(companionId = companionId, onNavigateBack = { navController.popBackStack() })
                }

                // === 导入聊天记录 ===
                composable(MainRoute.ImportChat(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    ImportChatScreen(
                        characterId = companionId,
                        onNavigateBack = { navController.popBackStack() }
                    )
                }

                // === 聊天 ===
                composable(MainRoute.Chat(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val companionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    var showThatDaySheet by remember { mutableStateOf(false) }
                    var showMemorySheet by remember { mutableStateOf(false) }
                    val recallViewModel: RecallViewModel = viewModel(key = "recall_$companionId") {
                        RecallViewModel(context.applicationContext as android.app.Application, companionId)
                    }
                    var characterName by remember { mutableStateOf("") }
                    LaunchedEffect(companionId) {
                        characterName = ServiceRegistry
                            .getOrThrow(com.zengqi.ai.database.repository.CompanionRepository::class.java)
                            .getCompanionById(companionId)?.name ?: ""
                    }
                    Box(modifier = Modifier.fillMaxSize()) {
                        ChatScreen(
                            companionId = companionId,
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToDetail = { navController.navigate("chat_detail/$it") },
                            onNavigateToVoiceCall = { navController.navigate("voice_call/$it") },
                            onNavigateToVideoCall = { navController.navigate("video_call/$it") },
                            onOpenThatDay = { showThatDaySheet = true },
                            onOpenMemory = { showMemorySheet = true }
                        )
                    }
                    if (showThatDaySheet) {
                        ThatDaySheet(
                            characterName = characterName.ifBlank { "TA" },
                            date = java.time.LocalDate.now().minusDays(1),
                            onDismiss = { showThatDaySheet = false },
                            viewModel = recallViewModel
                        )
                    }
                    if (showMemorySheet) {
                        MemorySheet(
                            characterName = characterName.ifBlank { "TA" },
                            onDismiss = { showMemorySheet = false },
                            viewModel = recallViewModel
                        )
                    }
                }
                composable(MainRoute.ChatDetail(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val detailCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    ChatDetailScreen(
                        companionId = detailCompanionId,
                        onNavigateBack = { navController.popBackStack() },
                        onSwitchCompanion = { newId ->
                            navController.navigate("chat/$newId") {
                                popUpTo("chat_detail/$detailCompanionId") { inclusive = true }
                            }
                        }
                    )
                }
                composable(MainRoute.VoiceCall(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val callCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    VoiceCallScreen(companionId = callCompanionId, onNavigateBack = { navController.popBackStack() })
                }
                composable(MainRoute.VideoCall(0).route.replace("0", "{companionId}"), arguments = listOf(navArgument("companionId") { type = NavType.LongType })) { backStackEntry ->
                    val callCompanionId = backStackEntry.arguments?.getLong("companionId") ?: 0L
                    VoiceCallScreen(
                        companionId = callCompanionId,
                        onNavigateBack = { navController.popBackStack() },
                        videoEnabled = true
                    )
                }

                // === 群聊 ===
                composable(MainRoute.Settings.route) { SettingsScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.TtsSettings.route) { TtsSettingsScreen(onNavigateBack = { navController.popBackStack() }, isDarkTheme = isDark) }
                composable(MainRoute.TokenUsage.route) { TokenUsageScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.Memory.route) { MemoryScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.Theme.route) { ThemeScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.Language.route) { LanguageScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.CheckUpdate.route) { CheckUpdateScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.About.route) { AboutScreen(onNavigateBack = { navController.popBackStack() }, onAgreementClick = { navController.navigate(MainRoute.AgreementView.route) }) }
                composable(MainRoute.AgreementView.route) { AgreementViewScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.FrameRate.route) { FrameRateScreen(onNavigateBack = { navController.popBackStack() }, activity = mainActivity) }
                composable(MainRoute.YandereMode.route) {
                    val manager = ServiceRegistry.get(YandereModeManager::class.java)
                    if (manager != null) {
                        YandereModeScreen(onNavigateBack = { navController.popBackStack() }, yandereModeManager = manager)
                    }
                }
                composable(MainRoute.Team.route) { TeamScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.Support.route) { SupportScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.Thanks.route) {
                    ThanksScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onViewFullList = { navController.navigate(MainRoute.ThanksFullList.route) }
                    )
                }
                composable(MainRoute.ThanksFullList.route) {
                    ThanksFullListScreen(onNavigateBack = { navController.popBackStack() })
                }
                composable(MainRoute.ContextMemory.route) { ContextMemoryScreen(onNavigateBack = { navController.popBackStack() }) }
                composable(MainRoute.OriginOSAdaption.route) {
                    OriginOSAdaptionScreen(onNavigateBack = { navController.popBackStack() })
                }
                composable(MainRoute.ExperimentalFeatures.route) {
                    ExperimentalFeaturesScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onYandereModeClick = { navController.navigate(MainRoute.YandereMode.route) }
                    )
                }
                composable(MainRoute.GeneralSettings.route) {
                    GeneralSettingsScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onLanguageClick = { navController.navigate(MainRoute.Language.route) },
                        onFrameRateClick = { navController.navigate(MainRoute.FrameRate.route) },
                        onTtsSettingsClick = { navController.navigate(MainRoute.TtsSettings.route) },
                        onTokenUsageClick = { navController.navigate(MainRoute.TokenUsage.route) },
                        onCheckUpdateClick = { navController.navigate(MainRoute.CheckUpdate.route) },
                        onOriginOSAdaptionClick = { navController.navigate(MainRoute.OriginOSAdaption.route) },
                        onExperimentalFeaturesClick = { navController.navigate(MainRoute.ExperimentalFeatures.route) }
                    )
                }
            }

            // 更新弹窗
            if (showUpdateDialog && updateInfo != null) {
                UpdateDialog(
                    updateInfo = updateInfo!!,
                    downloadProgress = downloadProgress,
                    onUpdate = {
                        updateInfo?.let { info ->
                            if (info.updateUrl.isNotEmpty()) {
                                if (updateManager.checkInstallPermission()) updateManager.startDownload(info.updateUrl)
                                else updateManager.requestInstallPermission(valActivity)
                            }
                        }
                    },
                    onCancel = { updateManager.ignoreThisVersion() },
                    onDismiss = { updateManager.dismissUpdate() }
                )
            }
        }
    }
}
