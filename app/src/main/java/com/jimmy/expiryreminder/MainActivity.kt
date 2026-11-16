package com.jimmy.expiryreminder

import android.Manifest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import com.jimmy.expiryreminder.ui.AppTheme
import com.jimmy.expiryreminder.ui.CategoryScreen
import com.jimmy.expiryreminder.ui.ConflictsScreen
import com.jimmy.expiryreminder.ui.EditScreen
import com.jimmy.expiryreminder.ui.HomeFilterState
import com.jimmy.expiryreminder.ui.HomeScreen
import com.jimmy.expiryreminder.ui.OcrProgressScreen
import com.jimmy.expiryreminder.ui.PickImagesScreen
import com.jimmy.expiryreminder.ui.SettingsScreen
import com.jimmy.expiryreminder.ui.SpaceScreen
import com.jimmy.expiryreminder.ui.TransferScreen
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val appContext = applicationContext
            if (granted) {
                appScope.launch {
                    val store = ExpiryStore.get()
                    val settings = SettingsStore.get()
                    ReminderService.syncScheduled(
                        appContext, store, settings.notifyEnabled.value, settings.notifyTime.value,
                        settings.useAlarmClock.value, settings.liveUpdateEnabled.value,
                    )
                }
            }
        }

    val appScope get() = (application as App).appScope

    companion object {
        /** 桌面小组件列表项点击时带入的物品 id。 */
        const val EXTRA_OPEN_ITEM_ID = "widget_item_id"

        /** 桌面小组件右上角「+」：直接打开新增页。 */
        const val EXTRA_OPEN_ADD = "widget_open_add"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 声明 edge-to-edge，并把系统栏图标外观跟随应用主题：
        // 浅色主题下用深色图标（默认白色图标在白底头部上不可见）
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val settings = SettingsStore.get()
            val themeMode by settings.themeMode.collectAsStateWithLifecycle()

            // 系统栏图标深浅跟随应用主题（system 跟系统，light 用深色图标，dark 用浅色图标）
            val darkTheme = when (themeMode) {
                com.jimmy.expiryreminder.core.SettingsStore.ThemeMode.dark -> true
                com.jimmy.expiryreminder.core.SettingsStore.ThemeMode.light -> false
                com.jimmy.expiryreminder.core.SettingsStore.ThemeMode.system ->
                    (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                        android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
            androidx.compose.runtime.SideEffect {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !darkTheme
                controller.isAppearanceLightNavigationBars = !darkTheme
            }

            // 首次启动请求通知权限
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                // 前台兜底：补发今天推送时刻已过、但闹钟被系统吞掉的那几条
                appScope.launch {
                    val store = ExpiryStore.get()
                    store.load(applicationContext)
                    ReminderService.runForegroundCheck(
                        applicationContext, store,
                        settings.notifyEnabled.value, settings.notifyTime.value,
                    )
                }
            }

            AppTheme(themeMode.name) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var screen by rememberSaveable(stateSaver = ScreenSaver) {
                        // 桌面小组件点条目进来时直接打开该物品的编辑页
                        val openItem = intent?.getStringExtra(EXTRA_OPEN_ITEM_ID)
                            ?.takeIf { it.isNotEmpty() }
                        val openAdd = intent?.getBooleanExtra(EXTRA_OPEN_ADD, false) == true
                        mutableStateOf<Screen>(
                            when {
                                openItem != null -> Screen.Edit(itemId = openItem)
                                openAdd -> Screen.Edit(itemId = null)
                                else -> Screen.Home
                            },
                        )
                    }
                    // 筛选状态放在导航层：进出编辑/设置页回来仍然是上次的搜索词与分类
                    val homeFilter = rememberSaveable(saver = HomeFilterState.Saver) {
                        HomeFilterState()
                    }
                    // 主页离开组合（跳编辑页等）时暂存它内部所有 rememberSaveable 状态，
                    // 返回时原样恢复——否则列表滚动位置会丢，回到顶部。
                    // 只包主页：编辑页等二级页每次进入都应是干净的，不恢复上次草稿。
                    val homeStateHolder = rememberSaveableStateHolder()
                    // 预测性返回：手指从边缘拖动时页面跟手缩小、右移，松手完成返回。
                    // 开关关闭时退回普通返回，行为一致但没有跟手动画。
                    var backProgress by remember { mutableFloatStateOf(0f) }
                    val uiScope = rememberCoroutineScope()
                    val predictiveBack by SettingsStore.get().predictiveBackEnabled
                        .collectAsStateWithLifecycle()
                    // 顶部返回箭头、编辑页「保存」也走同一段退出动画：
                    // 只拦系统手势的话，点按钮会「瞬间跳回主页」，看起来像没适配。
                    var manualBack by remember { mutableStateOf(false) }
                    fun goBack() {
                        if (screen != Screen.Home) manualBack = true
                    }
                    LaunchedEffect(manualBack) {
                        if (!manualBack) return@LaunchedEffect
                        animate(0f, 1f, animationSpec = tween(180)) { value, _ ->
                            backProgress = value
                        }
                        screen = Screen.Home
                        manualBack = false
                    }
                    // 手势完成后不立刻把进度清零：让新页面从缩小状态缓回全屏，
                    // 避免「松手瞬间弹一下」的割裂感。
                    LaunchedEffect(screen) {
                        if (screen == Screen.Home && backProgress > 0f) {
                            animate(backProgress, 0f, animationSpec = tween(220)) { value, _ ->
                                backProgress = value
                            }
                        }
                    }
                    val activity = LocalContext.current as? ComponentActivity
                    // Android 14+ 直接在 Activity 的 OnBackInvokedDispatcher 上注册动画回调：
                    // 和酷安一类应用一样按页面拿 onBackStarted/onBackProgressed，
                    // 不再依赖 Compose 封装层的转译，设置页/新增页手势才能稳定跟手。
                    DisposableEffect(activity, predictiveBack, screen) {
                        val dispatcher = activity?.onBackInvokedDispatcher
                        if (Build.VERSION.SDK_INT >= 34 && predictiveBack &&
                            screen != Screen.Home && dispatcher != null
                        ) {
                            val callback = object : OnBackAnimationCallback {
                                override fun onBackStarted(backEvent: BackEvent) {
                                    // 个别 ROM 首个事件 progress 为 0，给一点起始位移，避免看起来没反应
                                    backProgress = maxOf(backProgress, 0.08f)
                                }

                                override fun onBackProgressed(backEvent: BackEvent) {
                                    backProgress = backEvent.progress.coerceIn(0f, 1f)
                                }

                                override fun onBackCancelled() {
                                    uiScope.launch {
                                        animate(backProgress, 0f, animationSpec = tween(160)) { value, _ ->
                                            backProgress = value
                                        }
                                    }
                                }

                                override fun onBackInvoked() {
                                    screen = Screen.Home
                                }
                            }
                            dispatcher.registerOnBackInvokedCallback(
                                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback,
                            )
                            onDispose { dispatcher.unregisterOnBackInvokedCallback(callback) }
                        } else {
                            onDispose { }
                        }
                    }
                    // 低版本没有 OnBackAnimationCallback，沿用 Compose 的 PredictiveBackHandler
                    if (Build.VERSION.SDK_INT < 34) {
                        PredictiveBackHandler(enabled = predictiveBack && screen != Screen.Home) { progress ->
                            try {
                                progress.collect { event -> backProgress = event.progress }
                                screen = Screen.Home
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                uiScope.launch {
                                    animate(backProgress, 0f, animationSpec = tween(160)) { value, _ ->
                                        backProgress = value
                                    }
                                }
                                throw e
                            }
                        }
                    }
                    BackHandler(enabled = !predictiveBack && screen != Screen.Home) {
                        screen = Screen.Home
                    }
                    // 关键：在组合期把进度算成普通 Float，再交给 graphicsLayer。
                    // 直接在 graphicsLayer 的 lambda 里读 State 不会稳定触发重绘，
                    // 表现就是「手势走了但页面不动」。
                    val backP = backProgress.coerceIn(0f, 1f)
                    // 跟手时后面压一层暗色，让「页面被推走」有参照物（不跟随缩放）
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.22f * backP)),
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val s = 1f - 0.12f * backP
                                scaleX = s
                                scaleY = s
                                translationX = 64.dp.toPx() * backP
                                alpha = 1f - 0.28f * backP
                                transformOrigin = TransformOrigin(1f, 0.5f)
                                shape = RoundedCornerShape(24.dp)
                                clip = true
                            },
                    ) {
                    // 流体动效：进二级页从右侧轻推进入，回主页反向退出；
                    // 时长压到 220~260ms，避免「动画慢半拍」。
                    AnimatedContent(
                        targetState = screen,
                        transitionSpec = {
                            val forward = targetState !is Screen.Home
                            if (forward) {
                                // 进二级页：保留短距离横向层级感
                                (slideInHorizontally(tween(220)) { it / 10 } +
                                    fadeIn(tween(180))) togetherWith
                                    (slideOutHorizontally(tween(220)) { -it / 16 } +
                                        fadeOut(tween(140)))
                            } else {
                                // 回主页/保存完成：不再模拟返回手势的缩放+侧滑，
                                // 只用平台式淡入淡出，避免「保存像被拖回去」的生硬感
                                fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                            }
                        },
                        label = "screen",
                    ) { target ->
                    when (val s = target) {
                        is Screen.Home -> homeStateHolder.SaveableStateProvider(key = "home") {
                            HomeScreen(
                                filter = homeFilter,
                                onAdd = { defaultCategory, defaultSpace ->
                                    screen = Screen.Edit(
                                        itemId = null,
                                        defaultCategory = defaultCategory,
                                        defaultSpace = defaultSpace,
                                    )
                                },
                                onOcr = { defaultCategory, defaultSpace ->
                                    screen = Screen.PickImages(
                                        defaultCategory = defaultCategory,
                                        defaultSpace = defaultSpace,
                                    )
                                },
                                onEdit = { id -> screen = Screen.Edit(id) },
                                onSettings = { screen = Screen.Settings },
                                onCategories = { screen = Screen.Categories },
                                onSpaces = { screen = Screen.Spaces },
                                onShare = { ids -> screen = Screen.Transfer(ids) },
                            )
                        }
                        is Screen.PickImages -> PickImagesScreen(
                            onBack = { goBack() },
                            onConfirm = { uris ->
                                screen = Screen.OcrProgress(
                                    defaultCategory = s.defaultCategory,
                                    defaultSpace = s.defaultSpace,
                                    uris = uris.map { it.toString() },
                                )
                            },
                        )
                        is Screen.OcrProgress -> OcrProgressScreen(
                            uris = s.uris.mapNotNull { runCatching { Uri.parse(it) }.getOrNull() },
                            onCancel = {
                                // 回选图页，把已选照片带回去，不白拍
                                screen = Screen.PickImages(
                                    defaultCategory = s.defaultCategory,
                                    defaultSpace = s.defaultSpace,
                                    initialUris = s.uris,
                                )
                            },
                            onFinish = { draft ->
                                screen = Screen.Edit(
                                    itemId = null,
                                    defaultCategory = s.defaultCategory,
                                    defaultSpace = s.defaultSpace,
                                    prefill = draft,
                                )
                            },
                        )
                        is Screen.Edit -> EditScreen(
                            itemId = s.itemId,
                            defaultCategory = s.defaultCategory,
                            defaultSpace = s.defaultSpace,
                            prefill = s.prefill,
                            onDone = { goBack() },
                            onSaved = { screen = Screen.Home },
                        )
                        is Screen.Settings -> SettingsScreen(
                            onBack = { goBack() },
                            onTransfer = { screen = Screen.Transfer(emptyList()) },
                            onConflicts = { screen = Screen.Conflicts },
                        )
                        is Screen.Conflicts -> ConflictsScreen(
                            onBack = { goBack() },
                        )
                        is Screen.Categories -> CategoryScreen(
                            onBack = { goBack() },
                        )
                        is Screen.Spaces -> SpaceScreen(
                            onBack = { goBack() },
                        )
                        is Screen.Transfer -> TransferScreen(
                            itemIds = s.itemIds,
                            onBack = { goBack() },
                        )
                    }
                    }
                    }
                }
            }
        }
    }
}

sealed interface Screen {
    data object Home : Screen

    /** [defaultCategory] / [defaultSpace] 只在新增（[itemId] 为 null）时生效，来自主页当前的筛选。 */
    data class Edit(
        val itemId: String?,
        val defaultCategory: String? = null,
        /** 主页筛选的空间；「全部空间」「未分配」时为空，新增物品默认不归属空间。 */
        val defaultSpace: String? = null,
        /** 识别预填结果；普通新增/编辑为 null。不持久化，仅页面间传递。 */
        val prefill: com.jimmy.expiryreminder.ocr.PrefillDraft? = null,
    ) : Screen
    data object Settings : Screen

    /** 自动同步发现的疑似重复物品，逐条人工合并。 */
    data object Conflicts : Screen

    /** 分类管理（独立页面，不再是弹窗）。 */
    data object Categories : Screen

    /** 空间管理（独立页面）。 */
    data object Spaces : Screen

    /** 跨设备传输：从首页多选「分享」进来时带 [itemIds]，从设置进来时为空。 */
    data class Transfer(val itemIds: List<String> = emptyList()) : Screen

    /** 选图页。[initialUris] 用于从识别进度页返回时带回已选照片。 */
    data class PickImages(
        val defaultCategory: String? = null,
        val defaultSpace: String? = null,
        val initialUris: List<String> = emptyList(),
    ) : Screen

    /** 识别进度页。uri 存字符串，便于 rememberSaveable 序列化。 */
    data class OcrProgress(
        val defaultCategory: String? = null,
        val defaultSpace: String? = null,
        val uris: List<String> = emptyList(),
    ) : Screen
}

/**
 * Screen 不是 Bundle 可保存类型（自定义 sealed 类），直接 rememberSaveable 会在
 * 首帧组合时抛 IllegalArgumentException 导致启动即闪退，这里换成手动序列化。
 */
private val ScreenSaver = mapSaver(
    save = { screen ->
        when (screen) {
            is Screen.Edit -> mapOf(
                "name" to "edit",
                "itemId" to (screen.itemId ?: ""),
                "category" to (screen.defaultCategory ?: ""),
                "space" to (screen.defaultSpace ?: ""),
            )
            is Screen.Settings -> mapOf("name" to "settings")
            is Screen.Conflicts -> mapOf("name" to "conflicts")
            is Screen.Categories -> mapOf("name" to "categories")
            is Screen.Spaces -> mapOf("name" to "spaces")
            is Screen.Transfer -> mapOf(
                "name" to "transfer",
                "ids" to ArrayList(screen.itemIds),
            )
            is Screen.PickImages -> mapOf(
                "name" to "pick",
                "category" to (screen.defaultCategory ?: ""),
                "space" to (screen.defaultSpace ?: ""),
                "uris" to ArrayList(screen.initialUris),
            )
            is Screen.OcrProgress -> mapOf(
                "name" to "ocr",
                "category" to (screen.defaultCategory ?: ""),
                "space" to (screen.defaultSpace ?: ""),
                "uris" to ArrayList(screen.uris),
            )
            else -> mapOf("name" to "home")
        }
    },
    restore = { map ->
        when (map["name"]) {
            "settings" -> Screen.Settings
            "conflicts" -> Screen.Conflicts
            "categories" -> Screen.Categories
            "spaces" -> Screen.Spaces
            "transfer" -> Screen.Transfer(
                (map["ids"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            "edit" -> Screen.Edit(
                itemId = (map["itemId"] as? String)?.takeIf { it.isNotEmpty() },
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                defaultSpace = (map["space"] as? String)?.takeIf { it.isNotEmpty() },
            )
            "pick" -> Screen.PickImages(
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                defaultSpace = (map["space"] as? String)?.takeIf { it.isNotEmpty() },
                initialUris = (map["uris"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            "ocr" -> Screen.OcrProgress(
                defaultCategory = (map["category"] as? String)?.takeIf { it.isNotEmpty() },
                defaultSpace = (map["space"] as? String)?.takeIf { it.isNotEmpty() },
                uris = (map["uris"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
            )
            else -> Screen.Home
        }
    },
)
