package com.jimmy.expiryreminder

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
                        settings.useAlarmClock.value,
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

        /** 桌面小组件「+」左侧相机键：直接打开与主页相机按钮相同的选图（拍照）页。 */
        const val EXTRA_OPEN_CAMERA = "widget_open_camera"

        /**
         * 长按桌面图标的快捷方式带入的标识（见 res/xml/shortcuts.xml）。
         * 快捷方式 XML 的 extra 只能是字符串，所以不能复用上面的 Boolean extra。
         */
        const val EXTRA_SHORTCUT = "shortcut"

        /** 快捷方式：直接进新增页。 */
        const val SHORTCUT_ADD = "add"

        /** 快捷方式：直接进选图（拍照）页。 */
        const val SHORTCUT_CAMERA = "camera"
    }

    /**
     * 应用已在运行时再次收到启动 Intent（长按桌面快捷方式）走这里。
     * 用 State 而非直接设置 screen：screen 是 Compose 内部 rememberSaveable 状态，
     * 由 setContent 里的 LaunchedEffect 消费后再清空，等价于「一次性跳转指令」。
     */
    private val pendingNav = mutableStateOf<Intent?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 覆盖 Activity.intent，避免后续读取到上一次的旧 extra
        setIntent(intent)
        pendingNav.value = intent
    }

    /** 把启动 Intent 翻译成目标页面；普通点图标启动返回 null（停在主页）。 */
    private fun resolveScreen(intent: Intent?): Screen? {
        if (intent == null) return null
        // 桌面小组件：指定物品 / 新增 / 拍照
        intent.getStringExtra(EXTRA_OPEN_ITEM_ID)?.takeIf { it.isNotEmpty() }
            ?.let { return Screen.Edit(itemId = it) }
        if (intent.getBooleanExtra(EXTRA_OPEN_ADD, false)) return Screen.Edit(itemId = null)
        if (intent.getBooleanExtra(EXTRA_OPEN_CAMERA, false)) return Screen.PickImages()
        // 长按桌面图标的快捷方式
        return when (intent.getStringExtra(EXTRA_SHORTCUT)) {
            SHORTCUT_ADD -> Screen.Edit(itemId = null)
            SHORTCUT_CAMERA -> Screen.PickImages()
            else -> null
        }
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
                        // 冷启动首屏由启动 Intent 决定：长按图标的快捷方式。
                        // 应用已运行时的二次触发走 onNewIntent → pendingNav（见下）。
                        mutableStateOf(resolveScreen(intent) ?: Screen.Home)
                    }
                    // 应用已在前台时点快捷方式不会重建 Activity，系统走 onNewIntent，
                    // 这里消费掉这条「一次性跳转指令」再清空。
                    val navIntent by pendingNav
                    LaunchedEffect(navIntent) {
                        val target = resolveScreen(navIntent) ?: return@LaunchedEffect
                        screen = target
                        pendingNav.value = null
                    }
                    // 筛选状态放在导航层：进出编辑/设置页回来仍然是上次的搜索词与分类
                    val homeFilter = rememberSaveable(saver = HomeFilterState.Saver) {
                        HomeFilterState()
                    }
                    // 主页离开组合（跳编辑页等）时暂存它内部所有 rememberSaveable 状态，
                    // 返回时原样恢复——否则列表滚动位置会丢，回到顶部。
                    // 只包主页：编辑页等二级页每次进入都应是干净的，不恢复上次草稿。
                    val homeStateHolder = rememberSaveableStateHolder()
                    // 二级页面按返回键回到主页，不直接退出应用
                    BackHandler(enabled = screen != Screen.Home) {
                        screen = Screen.Home
                    }
                    when (val s = screen) {
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
                            onBack = { screen = Screen.Home },
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
                        // 按 itemId 分组：从「新增」切到「编辑某条已有物品」时仍停在同一个 when 分支、
                        // 同一个调用点，不给 key 的话 Compose 会复用上页的组合状态，
                        // 14 个 remember 字段的初值不会按新 existing 重新取，编辑页就会显示上一页的草稿。
                        is Screen.Edit -> key(s.itemId ?: "new") {
                            EditScreen(
                                itemId = s.itemId,
                                defaultCategory = s.defaultCategory,
                                defaultSpace = s.defaultSpace,
                                prefill = s.prefill,
                                // 新增时点选「相似物品」里的某条，直接切到那一条的编辑页
                                onEditExisting = { id -> screen = Screen.Edit(id) },
                                onDone = { screen = Screen.Home },
                            )
                        }
                        is Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Home },
                            onTransfer = { screen = Screen.Transfer(emptyList()) },
                            onConflicts = { screen = Screen.Conflicts },
                        )
                        is Screen.Conflicts -> ConflictsScreen(
                            onBack = { screen = Screen.Home },
                        )
                        is Screen.Categories -> CategoryScreen(
                            onBack = { screen = Screen.Home },
                        )
                        is Screen.Spaces -> SpaceScreen(
                            onBack = { screen = Screen.Home },
                        )
                        is Screen.Transfer -> TransferScreen(
                            itemIds = s.itemIds,
                            onBack = { screen = Screen.Home },
                        )
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
