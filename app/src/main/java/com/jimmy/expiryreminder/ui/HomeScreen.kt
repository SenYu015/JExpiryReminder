@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.jimmy.expiryreminder.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.core.Spaces
import com.jimmy.expiryreminder.core.remindDaysSummary
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.launch
import kotlin.math.abs

/** 备注里的换行、制表、连续空格：列表只显示一行，先压成单个空格。 */
private val SINGLE_LINE_WS = Regex("\\s+")

/**
 * 首页筛选状态（搜索词 + 分类 + 空间）：由导航层持有，进出编辑页后回来仍保留。
 * 用 listSaver 存字符串，进程被回收再恢复也不丢。
 */
@Stable
class HomeFilterState(
    keyword: String = "",
    category: String = "all",
    space: String = Spaces.ALL,
) {
    var keyword by mutableStateOf(keyword)
    var category by mutableStateOf(category)
    /** 当前空间：列表、统计卡、分类计数都按它过滤；默认「全部空间」，含未分配。 */
    var space by mutableStateOf(space)

    companion object {
        const val ALL = "all"

        val Saver = listSaver<HomeFilterState, String>(
            save = { listOf(it.keyword, it.category, it.space) },
            restore = {
                HomeFilterState(
                    keyword = it.getOrElse(0) { "" },
                    category = it.getOrElse(1) { ALL },
                    space = it.getOrElse(2) { Spaces.ALL },
                )
            },
        )
    }
}

/** 首页图片查看器的入参：[origin] 是发起点击的缩略图在屏幕上的位置，用于展开/缩回转场。 */
private data class ViewerState(
    val paths: List<String>,
    val index: Int,
    val origin: ImageOrigin?,
)

/** 参考稿样式首页：白底标题区 + 统计卡 + 搜索/筛选 + 状态分组列表。
 *
 * [filter] 由调用方持有，保证切页回来筛选不丢；
 * [onAdd] / [onOcr] 会带上当前筛选的分类与空间（「全部分类」「全部空间」「未分配」时为 null），
 * 新增页据此预选，避免在某个空间下新增还得再手动选一次空间。 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    filter: HomeFilterState,
    /** [defaultCategory] / [defaultSpace] 为当前筛选的分类与空间，无筛选时传 null。 */
    onAdd: (defaultCategory: String?, defaultSpace: String?) -> Unit,
    /** 图像识别入口：选图 → 识别 → 带结果进添加页。 */
    onOcr: (defaultCategory: String?, defaultSpace: String?) -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
    onCategories: () -> Unit,
    /** 空间管理页。 */
    onSpaces: () -> Unit,
    /** 多选「分享」：把选中的物品带到跨设备传输页。 */
    onShare: (List<String>) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val settings = SettingsStore.get()
    val notifyEnabled by settings.notifyEnabled.collectAsStateWithLifecycle()
    val notifyTime by settings.notifyTime.collectAsStateWithLifecycle()
    val useAlarmClock by settings.useAlarmClock.collectAsStateWithLifecycle()
    val statCards by settings.statCards.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<ExpiryItem?>(null) }
    // 多选批量操作弹窗
    var batchSpaceDialog by remember { mutableStateOf(false) }
    var batchCategoryDialog by remember { mutableStateOf(false) }
    var batchDeleteDialog by remember { mutableStateOf(false) }
    // 按空间分享
    var shareSpaceDialog by remember { mutableStateOf(false) }
    var categoryMenuOpen by remember { mutableStateOf(false) }
    var spaceMenuOpen by remember { mutableStateOf(false) }
    // 全屏图片查看器：图片路径 + 起始下标 + 发起点击的缩略图位置，null = 未打开
    var viewer by remember { mutableStateOf<ViewerState?>(null) }

    // 只在多选态拦截返回；主页本身不拦截，交给系统处理，
    // 这样「返回桌面」才能走 ColorOS 的预测性返回动画（缩小到桌面图标）。
    BackHandler(enabled = selectMode) {
        selectMode = false
        selected = emptySet()
    }

    // 数据就绪后同步一次排程（新增/修改/删除都会经 store，这里兜底）
    LaunchedEffect(state.items, notifyEnabled, notifyTime, useAlarmClock) {
        if (state.loaded) {
            ReminderService.syncScheduled(
                context, store, notifyEnabled, notifyTime, useAlarmClock,
            )
        }
    }

    // 筛选的分类被删掉（或导入的数据里没有）时回落到「全部分类」，避免筛出空列表
    LaunchedEffect(state.categories, state.categoryColors) {
        if (filter.category != HomeFilterState.ALL && store.categoryOf(filter.category) == null) {
            filter.category = HomeFilterState.ALL
        }
    }

    // 当前空间被删掉时回落到「全部空间」（未分配是合法状态，不回落）
    LaunchedEffect(state.spaces) {
        if (filter.space != Spaces.ALL &&
            filter.space.isNotEmpty() &&
            store.spaceOf(filter.space) == null
        ) {
            filter.space = Spaces.ALL
            filter.category = HomeFilterState.ALL
        }
    }

    // 先只按当前空间 + 搜索词筛一遍：下拉里的分类计数 = 点进去后能看到的条数，与统计卡「全部」同一口径
    val spaceItems = remember(state, filter.space) { store.itemsInSpace(filter.space) }
    val keywordMatched = remember(spaceItems, filter.keyword) {
        val kw = filter.keyword.trim().lowercase()
        spaceItems.filter { item ->
            kw.isEmpty() || item.name.lowercase().contains(kw) || item.note.lowercase().contains(kw)
        }
    }
    val categoryCounts = remember(keywordMatched) {
        keywordMatched.groupingBy { it.category }.eachCount()
    }
    val filtered = remember(keywordMatched, filter.category) {
        if (filter.category == HomeFilterState.ALL) keywordMatched
        else keywordMatched.filter { it.category == filter.category }
    }
    val grouped = remember(filtered) {
        // 按状态固定顺序输出非空分组
        val byStatus = filtered.groupBy { store.statusOf(it) }
        ExpiryStatus.entries.mapNotNull { s -> byStatus[s]?.let { s to it } }
    }
    val categoryLabel = if (filter.category == HomeFilterState.ALL) "全部分类"
    else store.categoryOptions().firstOrNull { it.id == filter.category }?.label ?: "全部分类"

    // 可左右切换的分类环 =「全部分类」+ 当前空间下至少有一条未归档记录的分类。
    // 空分类不进环，滑动不会切出一片空白；这里刻意不跟搜索词联动，避免搜索时环忽长忽短。
    val switchableIds = remember(spaceItems) {
        val counts = spaceItems.groupingBy { it.category }.eachCount()
        buildList {
            add(HomeFilterState.ALL)
            store.categoryOptions().forEach { if ((counts[it.id] ?: 0) > 0) add(it.id) }
        }
    }
    val switchIndex = switchableIds.indexOf(filter.category).coerceAtLeast(0)
    val canSwitch = switchableIds.size > 1

    // 切换方向：1 = 往后翻（新内容从右侧滑进来），-1 = 往前翻（从左侧滑进来）。
    // 方向决定动画从哪一侧进场，用户滑动时才知道自己翻到了哪边。
    var slideDir by remember { mutableStateOf(1) }
    val slideShiftPx = with(LocalDensity.current) { 64.dp.toPx() }
    // 首次进页面不播动画（否则一进来列表就滑一下）
    var firstComposition by remember { mutableStateOf(true) }
    // 每换一次分类就新建一个从 ±1 起跳的 Animatable：起始偏移在组合期就位，
    // 不会出现「先按最终位置画一帧、再跳回去滑进来」的闪一下
    val slide = remember(filter.category) {
        Animatable(if (firstComposition) 0f else slideDir.toFloat())
    }
    LaunchedEffect(slide) {
        if (slide.value != 0f) {
            slide.animateTo(0f, tween(durationMillis = 260, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(Unit) { firstComposition = false }

    // step = 1 下一个 / -1 上一个，走到头绕回另一端
    val switchCategory: (Int) -> Unit = { step ->
        if (canSwitch) {
            val size = switchableIds.size
            val next = ((switchIndex + step) % size + size) % size
            if (next != switchIndex) {
                slideDir = if (step >= 0) 1 else -1
                filter.category = switchableIds[next]
            }
        }
    }

    // 下拉里直接点某个分类：按环上索引先后推方向，不在环里的（空分类）按往后翻处理
    val selectCategory: (String) -> Unit = { id ->
        val from = switchableIds.indexOf(filter.category)
        val to = switchableIds.indexOf(id)
        slideDir = if (from >= 0 && to >= 0 && to < from) -1 else 1
        filter.category = id
    }

    // 真的换了分类才回顶部；从编辑页返回时保留原来的滚动位置
    val listState = rememberLazyListState()
    var scrolledCategory by remember { mutableStateOf(filter.category) }
    LaunchedEffect(filter.category) {
        if (scrolledCategory != filter.category) {
            scrolledCategory = filter.category
            if (filtered.isNotEmpty()) listState.scrollToItem(0)
        }
    }

    // 新增/识别带上的分类：「全部分类」= 无筛选，交给新增页用默认分类；其余带上筛选的分类
    val targetCategory = filter.category.takeIf {
        it != HomeFilterState.ALL && store.categoryOf(it) != null
    }
    // 新增/识别带上的空间：同上，「全部空间」/「未分配」/ 已被删掉的空间都传 null
    val targetSpace = filter.space.takeIf {
        it != Spaces.ALL && it.isNotEmpty() && store.spaceOf(it) != null
    }

    // ------------------------------- 顶部磨砂 / 底部浮动：高度测量与滚动进度
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var topBarHeightPx by remember { mutableIntStateOf(0) }
    // 遮罩强度只跟滚动距离有关：滚到顶 0（完全透明，露出干净底色），往下 28dp 内就升满。
    // 上升区间必须短——内容刚进入遮罩时如果遮罩还是半透明，标题就会和背后的列表文字重影；
    // 一旦升满，真正的淡出交给遮罩自身的竖渐变（底部全透明）去完成。
    // 不做补间动画，值在 draw 阶段才读——滚动时只重绘、不重组，跟手且不掉帧。
    val frostSpanPx = with(density) { 28.dp.toPx() }
    val frostAlpha: () -> Float = {
        when {
            selectMode -> 1f
            listState.firstVisibleItemIndex > 0 -> 1f
            else -> (listState.firstVisibleItemScrollOffset / frostSpanPx).coerceIn(0f, 1f)
        }
    }
    val frostColor = MaterialTheme.colorScheme.background
    // 遮罩向下多铺一小段（24dp）露出淡出带：列表第一条正好从这段之后开始，
    // 静止时不会被压白，滚动时卡片就是在这 24dp 里由清晰过渡到消失
    val maskTail = 12.dp
    // 内容让位：固定块 + 淡出带；底部留出浮动条高度（都以实测为准，换字号也不会错位）
    val topContentPadding = if (topBarHeightPx > 0) {
        with(density) { topBarHeightPx.toDp() } + maskTail
    } else {
        statusTop + 92.dp + maskTail
    }
    // 底部浮动条高度是固定 dp（54/52/58 取最高 + 上下 12dp 内边距），不随字号变化，
    // 所以直接算常量，省一次测量回调；再留 24dp 余量，最后一条不会贴到按钮上
    val bottomContentPadding = if (selectMode) 16.dp else navBottom + 94.dp

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        // 顶部磨砂头与底部浮动条都要「压」在内容上，内容必须能滚到状态栏/导航栏底下，
        // 所以系统栏内边距自己接管，不让 Scaffold 先把内容区裁开
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // 多选态的批量操作条仍然占位，不做浮动
            if (selectMode) {
                SelectionActionBar(
                    canAct = selected.isNotEmpty(),
                    onSpace = { batchSpaceDialog = true },
                    onCategory = { batchCategoryDialog = true },
                    onShare = {
                        val ids = selected.toList()
                        selectMode = false
                        selected = emptySet()
                        onShare(ids)
                    },
                    onDelete = { batchDeleteDialog = true },
                )
            }
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // ---------------------------------------------- 整页一个滚动容器
            // 只有列表在这条 LazyColumn 里滚；标题、统计卡、分类行都是上方固定块，
            // 列表滚到固定块下面时被遮罩逐级淡出。
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .categorySlide({ slide.value }, slideShiftPx)
                    // 整页右滑切上一个分类；左滑不接管，列表行照旧左滑删除
                    .swipeRightToSwitchCategory { if (!selectMode) switchCategory(-1) },
                state = listState,
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = topContentPadding,
                    bottom = bottomContentPadding,
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // ---------------------------------------------- 列表 / 空态
                if (filtered.isEmpty()) {
                    // 空态与列表共用同一套进场动画，切到没有记录的分类时同样有「翻过去了」的反馈
                    item(key = "empty") {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillParentMaxHeight(0.66f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Box(
                                    Modifier
                                        .size(64.dp)
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant, CircleShape,
                                        ),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        Icons.Filled.DateRange,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    if (state.loaded) "暂无物品" else "加载中…",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (state.loaded) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "点底部 + 添加第一件物品",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    grouped.forEach { (status, items) ->
                        item(key = "header_${status.name}") {
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier
                                        .size(8.dp)
                                        .background(hexColor(status.color), CircleShape),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    status.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    "${items.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(items.size, key = { items[it].id }) { index ->
                            val item = items[index]
                            // 新增/删除/移动时条目自带位移动画，接近流体动效的连续感
                            Box(Modifier.animateItem()) {
                                ItemRow(
                                    item = item,
                                    store = store,
                                    selectMode = selectMode,
                                    selected = item.id in selected,
                                    showSpace = filter.space == Spaces.ALL,
                                    onClick = {
                                        if (selectMode) {
                                            selected =
                                                if (item.id in selected) selected - item.id
                                                else selected + item.id
                                        } else onEdit(item.id)
                                    },
                                    onLongClick = {
                                        if (!selectMode) {
                                            selectMode = true
                                            selected = setOf(item.id)
                                        }
                                    },
                                    onSwipeDelete = { pendingDelete = item },
                                    onViewImages = { origin ->
                                        viewer = ViewerState(item.images.map { it.path }, 0, origin)
                                    },
                                )
                            }
                        }
                    }
                }
            }

            // ---------------------------------------------- 顶部固定遮罩（渐变淡出）
            // 固定在页面顶部，本身是一条贯穿的竖向渐变：顶端不透明，向下逐级变淡，
            // 到遮罩底缘完全透明。内容滚进来时被逐级「吃掉」，是连续淡出而不是撞上一条边界。
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { topBarHeightPx = it.size.height }
                    .drawBehind {
                        val a = frostAlpha()
                        if (a <= 0f) return@drawBehind
                        // 固定块高度 = 遮罩的实心主体；再向下多铺 maskTail 做过渡带，
                        // 淡化全在过渡带里完成，固定块里的文字始终压在足够实的底上，读得清。
                        val total = size.height + maskTail.toPx()
                        // 一条从遮罩底缘往上连续变实的曲线（smoothstep）：底缘全透明，
                        // 约 120dp 内平滑收满，再往上保持不透明。
                        // 不用「实心段 + 16dp 突然淡出」的硬分段，也不会让中段太透、
                        // 把固定块后面的列表卡片透出来。
                        fun ss(x: Float): Float = x * x * (3f - 2f * x)
                        // 渐变带高度：固定块下缘 40dp + 向下多铺的 12dp，
                        // 上方的统计卡、副标题保持全实，不会透出背后的列表
                        val fadePx = 52.dp.toPx()
                        val frac = (fadePx / total).coerceIn(0.05f, 1f)
                        drawRect(
                            brush = Brush.verticalGradient(
                                *(0..8).map { i ->
                                    val t = i / 8f              // 0 = 遮罩顶部，1 = 遮罩底缘
                                    val u = 1f - t              // 距底缘的比例
                                    t to frostColor.copy(
                                        alpha = a * ss((u / frac).coerceIn(0f, 1f)),
                                    )
                                }.toTypedArray()
                            ),
                            size = Size(size.width, total),
                        )
                    },
            ) {
                Box(Modifier.fillMaxWidth()) {
                    if (selectMode) {
                        // ---------------------------------- 多选态头部
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(horizontal = 4.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = { selectMode = false; selected = emptySet() }) {
                                Icon(Icons.Filled.Close, contentDescription = "退出多选")
                            }
                            Text(
                                "已选 ${selected.size} 项",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f),
                            )
                            // 全选/取消全选：列表当前筛选结果里的全部物品
                            TextButton(
                                onClick = {
                                    val all = filtered.map { it.id }.toSet()
                                    selected = if (selected.containsAll(all) && all.isNotEmpty()) {
                                        emptySet()
                                    } else {
                                        all
                                    }
                                },
                            ) {
                                val all = filtered.map { it.id }.toSet()
                                Text(
                                    if (selected.containsAll(all) && all.isNotEmpty()) "取消全选"
                                    else "全选"
                                )
                            }
                        }
                    } else {
                        // ---------------------------------- 常规头部：标题 + 空间 + 设置
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "到期提醒",
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                // 标题块与右侧按钮组之间留固定呼吸位，标题长短都不挤在一起
                                Spacer(Modifier.width(12.dp))
                                // 空间切换：当前空间名 + 下拉（同时是空间管理的入口）
                                Box {
                                    Surface(
                                        onClick = { spaceMenuOpen = true },
                                        shape = RoundedCornerShape(20.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        shadowElevation = 2.dp,
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.outlineVariant,
                                        ),
                                        modifier = Modifier.height(40.dp),
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Box(
                                                Modifier
                                                    .size(8.dp)
                                                    .background(
                                                        hexColor(store.spaceColorOf(filter.space)),
                                                        CircleShape,
                                                    ),
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text(
                                                store.spaceLabelOf(filter.space),
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = 84.dp),
                                            )
                                            Spacer(Modifier.width(2.dp))
                                            Icon(
                                                Icons.Filled.KeyboardArrowDown,
                                                contentDescription = "切换空间",
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp),
                                            )
                                        }
                                    }
                                    DropdownMenu(
                                        expanded = spaceMenuOpen,
                                        onDismissRequest = { spaceMenuOpen = false },
                                        shape = RoundedCornerShape(12.dp),
                                    ) {
                                        // 全部空间（默认）：所有空间 + 未分配的物品一起看
                                        DropdownMenuItem(
                                            text = { Text(Spaces.ALL_LABEL) },
                                            leadingIcon = {
                                                Box(
                                                    Modifier.size(10.dp).background(
                                                        hexColor(store.spaceColorOf(Spaces.ALL)),
                                                        RoundedCornerShape(5.dp),
                                                    )
                                                )
                                            },
                                            trailingIcon = { CountBadge(store.itemCountInSpace(Spaces.ALL)) },
                                            onClick = {
                                                if (filter.space != Spaces.ALL) {
                                                    filter.space = Spaces.ALL
                                                    filter.category = HomeFilterState.ALL
                                                }
                                                spaceMenuOpen = false
                                            },
                                        )
                                        androidx.compose.material3.HorizontalDivider()
                                        for (space in store.spaceOptions()) {
                                            DropdownMenuItem(
                                                text = { Text(space.label) },
                                                leadingIcon = {
                                                    Box(
                                                        Modifier.size(10.dp).background(
                                                            hexColor(space.color), RoundedCornerShape(5.dp),
                                                        )
                                                    )
                                                },
                                                trailingIcon = {
                                                    CountBadge(store.itemCountInSpace(space.id))
                                                },
                                                onClick = {
                                                    if (filter.space != space.id) {
                                                        filter.space = space.id
                                                        // 分类计数跟着空间变，切空间后回到「全部分类」最不容易迷惑
                                                        filter.category = HomeFilterState.ALL
                                                    }
                                                    spaceMenuOpen = false
                                                },
                                            )
                                        }
                                        androidx.compose.material3.HorizontalDivider()
                                        // 未分配：物品可以不属于任何空间
                                        DropdownMenuItem(
                                            text = { Text(Spaces.UNASSIGNED_LABEL) },
                                            leadingIcon = {
                                                Box(
                                                    Modifier.size(10.dp).background(
                                                        hexColor(store.spaceColorOf("")),
                                                        RoundedCornerShape(5.dp),
                                                    )
                                                )
                                            },
                                            trailingIcon = { CountBadge(store.itemCountInSpace("")) },
                                            onClick = {
                                                if (filter.space.isNotEmpty()) {
                                                    filter.space = ""
                                                    filter.category = HomeFilterState.ALL
                                                }
                                                spaceMenuOpen = false
                                            },
                                        )
                                        androidx.compose.material3.HorizontalDivider()
                                        DropdownMenuItem(
                                            text = { Text("按空间分享…") },
                                            onClick = {
                                                spaceMenuOpen = false
                                                shareSpaceDialog = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("管理空间…") },
                                            onClick = {
                                                spaceMenuOpen = false
                                                onSpaces()
                                            },
                                        )
                                    }
                                }
                                // 与「全部空间」胶囊之间留 10dp：两颗都是 40dp 高，视觉上成组但不相连
                                Spacer(Modifier.width(10.dp))
                                Surface(
                                    onClick = onSettings,
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.surface,
                                    shadowElevation = 2.dp,
                                    border = androidx.compose.foundation.BorderStroke(
                                        1.dp, MaterialTheme.colorScheme.outlineVariant,
                                    ),
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Icon(
                                            Icons.Filled.Settings,
                                            contentDescription = "设置",
                                            tint = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.size(20.dp),
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "食品、药品、证件的有效期管理",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                // ------------------------------------------ 统计卡（数量与种类由设置决定）
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    // 4 张及以上收窄内边距和字号，保证 5 张也能一行放得下
                    val dense = statCards.size >= 4
                    for (card in statCards) {
                        StatCard(
                            count = card.status?.let { countOf(grouped, it) } ?: filtered.size,
                            label = card.label,
                            accent = card.status?.let { hexColor(it.color) }
                                ?: MaterialTheme.colorScheme.onSurface,
                            dense = dense,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }


                // ------------------------------------------ 分类切换器 + 分类/多选
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 分类下拉：点开菜单直选。切换分类靠整页右滑，这里不再放左右箭头
                    Box(Modifier.weight(1f)) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .frostedPanel()
                                .clickable { categoryMenuOpen = true },
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 容器宽度定死，这里换文字不会引起布局跳动；
                                // 竖向滚动淡入，和列表的横向滑动呼应出「翻页」的方向感
                                AnimatedContent(
                                    targetState = categoryLabel,
                                    transitionSpec = {
                                        val up = slideDir > 0
                                        (slideInVertically(tween(180)) { h -> if (up) h else -h } +
                                            fadeIn(tween(180))) togetherWith
                                            (slideOutVertically(tween(140)) { h -> if (up) -h else h } +
                                                fadeOut(tween(120)))
                                    },
                                    modifier = Modifier.weight(1f),
                                    label = "categoryLabel",
                                ) { label ->
                                    Text(
                                        label,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    Icons.Filled.KeyboardArrowDown, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = categoryMenuOpen,
                            onDismissRequest = { categoryMenuOpen = false },
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            // 每一项右侧显示该分类的记录数（未归档，随搜索词联动）
                            DropdownMenuItem(
                                text = { Text("全部分类") },
                                // 占位，让文案与下面带色点的分类项左对齐
                                leadingIcon = { Box(Modifier.size(10.dp)) },
                                trailingIcon = { CountBadge(keywordMatched.size) },
                                onClick = {
                                    selectCategory(HomeFilterState.ALL)
                                    categoryMenuOpen = false
                                },
                            )
                            for (cat in store.categoryOptions()) {
                                DropdownMenuItem(
                                    text = { Text(cat.label) },
                                    leadingIcon = {
                                        Box(
                                            Modifier.size(10.dp).background(
                                                hexColor(cat.color), RoundedCornerShape(5.dp),
                                            )
                                        )
                                    },
                                    trailingIcon = { CountBadge(categoryCounts[cat.id] ?: 0) },
                                    onClick = {
                                        selectCategory(cat.id)
                                        categoryMenuOpen = false
                                    },
                                )
                            }
                        }
                    }

                    // 分类管理（独立页面）：与搜索框同一套磨砂浮起效果
                    Box(
                        Modifier
                            .frostedPanel()
                            .clickable(onClick = onCategories),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "分类管理",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                            )
                        }
                    }

                    // 多选 / 退出多选（同一颗按钮切换）
                    val accent = if (selectMode) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                    Box(
                        Modifier
                            .frostedPanel(
                                borderColor = if (selectMode) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color.White.copy(alpha = 0.55f)
                                },
                            )
                            .clickable {
                                if (selectMode) {
                                    selectMode = false
                                    selected = emptySet()
                                } else {
                                    selectMode = true
                                }
                            },
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (selectMode) Icons.Filled.Close else Icons.Filled.Check,
                                contentDescription = null,
                                tint = accent, modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (selectMode) "退出多选" else "多选",
                                color = accent,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            // ---------------------------------------------- 底部浮动操作区
            if (!selectMode) {
                HomeFloatingBar(
                    keyword = filter.keyword,
                    onKeywordChange = { filter.keyword = it },
                    onOcr = { onOcr(targetCategory, targetSpace) },
                    onAdd = { onAdd(targetCategory, targetSpace) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除物品") },
            text = { Text("确定删除「${item.name}」吗？相关图片也会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    val target = item
                    pendingDelete = null
                    scope.launch { store.removeItem(context, target.id) }
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    // 多选：批量切换空间
    if (batchSpaceDialog) {
        BatchSpaceDialog(
            count = selected.size,
            onPick = { spaceId ->
                batchSpaceDialog = false
                val ids = selected.toList()
                scope.launch {
                    val moved = store.moveItemsToSpace(context, ids, spaceId)
                    selectMode = false
                    selected = emptySet()
                    Toast.makeText(
                        context,
                        "已把 $moved 件移到「${store.spaceLabelOf(spaceId)}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { batchSpaceDialog = false },
        )
    }

    // 多选：批量切换分类
    if (batchCategoryDialog) {
        BatchCategoryDialog(
            count = selected.size,
            onPick = { categoryId ->
                batchCategoryDialog = false
                val ids = selected.toList()
                scope.launch {
                    val changed = store.setItemsCategory(context, ids, categoryId)
                    selectMode = false
                    selected = emptySet()
                    Toast.makeText(
                        context,
                        "已把 $changed 件改为「${store.categoryLabelOf(categoryId)}」",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            onDismiss = { batchCategoryDialog = false },
        )
    }

    // 多选：批量删除，先确认一次
    if (batchDeleteDialog) {
        AlertDialog(
            onDismissRequest = { batchDeleteDialog = false },
            title = { Text("删除 ${selected.size} 件物品") },
            text = { Text("相关图片也会一并删除，此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    batchDeleteDialog = false
                    val ids = selected.toList()
                    scope.launch {
                        store.removeItems(context, ids)
                        selectMode = false
                        selected = emptySet()
                    }
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { batchDeleteDialog = false }) { Text("取消") }
            },
        )
    }

    // 按空间分享：把一个空间下的物品整体发到对方设备
    if (shareSpaceDialog) {
        ShareSpaceDialog(
            onPick = { spaceId ->
                shareSpaceDialog = false
                val ids = store.itemsInSpace(spaceId).map { it.id }
                if (ids.isEmpty()) {
                    Toast.makeText(context, "该空间下没有可分享的物品", Toast.LENGTH_SHORT).show()
                } else {
                    onShare(ids)
                }
            },
            onDismiss = { shareSpaceDialog = false },
        )
    }

    viewer?.let { state ->
        ImageViewerOverlay(
            paths = state.paths,
            initialIndex = state.index,
            origin = state.origin,
            onDismiss = { viewer = null },
        )
    }
}

/**
 * 首页统一的「磨砂浮起」底：半透明面板色 + 柔投影 + 一圈亮边。
 *
 * 为什么不直接用 `Surface(color = 半透明, shadowElevation = x)`：
 * M3 的 Surface 会把容器色当作阴影底板一起画，颜色带 alpha 时这块底板就从圆角外
 * 透出一片**白色矩形**（统计卡、分类行、底部控件都中过招）。这里改成
 * `Modifier.shadow` 显式给一组中性阴影色，再单独叠背景层和亮边，绕开这个问题。
 *
 * 末尾的 [Modifier.clip] 让水波纹和子内容都收在圆角里，阴影因为 `clip = false` 不被切掉。
 */
@Composable
private fun Modifier.frostedPanel(
    shape: Shape = RoundedCornerShape(18.dp),
    elevation: Dp = 12.dp,
    fill: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = Color.White.copy(alpha = 0.55f),
    borderWidth: Dp = 0.8.dp,
): Modifier = this
    .shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color(0x14000000),
        spotColor = Color(0x33000000),
    )
    .background(fill.copy(alpha = 0.90f), shape)
    .border(borderWidth, borderColor, shape)
    .clip(shape)

private fun countOf(
    grouped: List<Pair<ExpiryStatus, List<ExpiryItem>>>,
    status: ExpiryStatus,
): Int = grouped.firstOrNull { it.first == status }?.second?.size ?: 0

/**
 * 底部浮动操作区：搜索胶囊 + 独立拍照圆钮 + 绿色新增圆钮。
 *
 * 三者各自成块浮在内容之上（不共用一条底板），高度统一 56dp、间距统一 10dp，
 * 固定吸底不随列表滚动；搜索占满左侧剩余宽度，拍照夹在中间。
 * 整条背后压一层「透明 → 背景色」的渐隐，列表滚到底时自然化开，不会撞到按钮上。
 * 键盘弹出时被顶到键盘上方，边输边看结果。
 */
@Composable
private fun HomeFloatingBar(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    onOcr: () -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            // 底板彻底透明：只有三个控件本身浮在内容上，列表内容从它们之间和下面穿过
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 搜索胶囊：半透明磨砂白 + 亮边 + 柔和投影，没有底板也不会糊在内容里
            Box(
                Modifier
                    .weight(1f)
                    .height(56.dp)
                    .frostedPanel(RoundedCornerShape(28.dp)),
            ) {
                Row(
                    Modifier
                        .fillMaxSize()
                        .padding(start = 18.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (keyword.isEmpty()) {
                            Text(
                                "搜索名称或备注",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = keyword,
                            onValueChange = onKeywordChange,
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (keyword.isNotEmpty()) {
                        IconButton(
                            onClick = { onKeywordChange("") },
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "清空搜索",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
            // 拍照：从搜索框里独立出来的浮动圆钮，夹在搜索和新增中间
            FloatCircleButton(
                size = 56.dp,
                fill = MaterialTheme.colorScheme.surface,
                elevation = 12.dp,
                description = "拍照识别",
                onClick = onOcr,
            ) {
                CameraGlyph(
                    color = MaterialTheme.colorScheme.primary,
                    holeColor = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(24.dp),
                )
            }
            // 新增：与搜索框同一套磨砂浮起效果，+ 号用主色保持主操作辨识度
            FloatCircleButton(
                size = 56.dp,
                fill = MaterialTheme.colorScheme.surface,
                elevation = 12.dp,
                description = "新增",
                onClick = onAdd,
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** 浮动圆钮：磨砂底 + 阴影 + 圆形裁切，尺寸、阴影、亮边由调用方定。 */
@Composable
private fun FloatCircleButton(
    size: Dp,
    fill: Color,
    elevation: Dp,
    description: String,
    onClick: () -> Unit,
    borderColor: Color = Color.White.copy(alpha = 0.55f),
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .semantics { contentDescription = description }
            .frostedPanel(
                shape = CircleShape,
                elevation = elevation,
                fill = fill,
                borderColor = borderColor,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/**
 * 切分类的进场效果：整块内容沿滑动方向滑入并淡入（progress 为 0 时完全归位）。
 * 读的是 [Animatable] 的值，在 draw 阶段才订阅，不会每帧触发重组。
 */
private fun Modifier.categorySlide(progress: () -> Float, shiftPx: Float): Modifier =
    graphicsLayer {
        val p = progress()
        translationX = p * shiftPx
        alpha = 1f - 0.6f * abs(p)
    }

/**
 * 整页右滑 = 切到上一个有记录的分类；左滑不接管，交给列表行自己的左滑删除。
 *
 * 为什么要在 Initial 阶段手工判定：列表行的横向手势归 [SwipeToDismissBox]（anchoredDraggable，
 * 两个方向都会被它吃掉），普通 drag 检测挂在父亲上抢不过子节点。这里先在 Initial 阶段
 * 看一眼方向——向右才 consume（行拿到的位移已被消费，不会跟着动），向左/上下直接放手，
 * 删除和滚动完全不受影响。
 */
@Composable
private fun Modifier.swipeRightToSwitchCategory(onPrevious: () -> Unit): Modifier {
    val latest by rememberUpdatedState(onPrevious)
    val threshold = with(LocalDensity.current) { 36.dp.toPx() }
    return pointerInput(Unit) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var dx = 0f
            var dy = 0f
            var decided = false
            var capturing = false
            var done = false
            while (!done) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = change.positionChangeIgnoreConsumed()
                dx += delta.x
                dy += delta.y
                if (!decided && (abs(dx) > slop || abs(dy) > slop)) {
                    decided = true
                    // 只有明确「向右且比上下位移更大」才接管，其余一律放手
                    capturing = dx > 0 && dx > abs(dy)
                    if (!capturing) done = true
                }
                if (capturing) change.consume()
                if (change.changedToUpIgnoreConsumed()) done = true
            }
            if (capturing && dx >= threshold) latest()
        }
    }
}

/** 分类下拉里的记录数：右对齐的灰色小字，0 条时更淡。 */
@Composable
private fun CountBadge(count: Int) {
    Text(
        "$count",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
            alpha = if (count == 0) 0.45f else 1f,
        ),
        textAlign = TextAlign.End,
        modifier = Modifier.padding(start = 16.dp).widthIn(min = 14.dp),
    )
}

/** 参考稿统计卡：白底、左对齐彩色大数字 + 灰色标签。[dense] 用于 4 张以上时收窄，避免一行放不下。 */
@Composable
private fun StatCard(
    count: Int,
    label: String,
    accent: Color,
    dense: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // 与搜索框、分类行同一套磨砂浮起效果：半透明白底 + 白色亮边 + 柔投影
    Box(modifier.frostedPanel()) {
        Column(
            Modifier.padding(
                horizontal = if (dense) 8.dp else 14.dp,
                vertical = if (dense) 10.dp else 12.dp,
            )
        ) {
            Text(
                "$count",
                style = if (dense) MaterialTheme.typography.titleLarge
                else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(
    item: ExpiryItem,
    store: ExpiryStore,
    selectMode: Boolean,
    selected: Boolean,
    /** 「全部空间」筛选下显示归属空间，否则列表里分不清哪件属于哪个空间。 */
    showSpace: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSwipeDelete: () -> Unit,
    /** 点击缩略图看大图，带回缩略图的屏幕位置供查看器做展开/缩回转场。 */
    onViewImages: (ImageOrigin?) -> Unit,
) {
    val days = Dates.daysUntil(item.expireAt)
    val status = store.statusOf(item)
    // 缩略图在屏幕上的位置：布局后记录，点击看大图时交给查看器作为动画起点/终点。
    // 圆角必须与缩略图一致（8dp），否则缩回终点形状不符会闪一下。
    val thumbRadiusPx = with(LocalDensity.current) { 8.dp.toPx() }
    var thumbOrigin by remember { mutableStateOf<ImageOrigin?>(null) }
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && !selectMode) {
                onSwipeDelete()
            }
            // 无论确认与否都弹回原位，删除交给对话框
            false
        },
        positionalThreshold = { it * 0.5f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        // 多选态下整行都是「点选」区域，禁止左滑，避免露出删除红底
        enableDismissFromEndToStart = !selectMode,
        backgroundContent = {
            // 多选态不画删除底色：卡片选中色是半透明的，红底会透上来变成一片红
            if (!selectMode) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.error, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Icon(
                        Icons.Filled.Delete, contentDescription = "删除",
                        tint = Color.White, modifier = Modifier.padding(end = 20.dp),
                    )
                }
            }
        },
    ) {
        Card(
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            colors = CardDefaults.cardColors(
                // 选中色要和底色混成不透明色，否则会透出后面的内容
                containerColor = if (selected) {
                    androidx.compose.ui.graphics.lerp(
                        MaterialTheme.colorScheme.surface,
                        MaterialTheme.colorScheme.primary,
                        0.10f,
                    )
                } else {
                    MaterialTheme.colorScheme.surface
                },
            ),
            // 参考通知中心：干净的大圆角白卡，去掉四周描边与彩色角落
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 分类色条：固定 34dp 的短条，垂直居中；不再跟着行高拉长
                Box(
                    Modifier
                        .padding(start = 8.dp)
                        .width(4.dp)
                        .height(34.dp)
                        .background(
                            hexColor(store.categoryColorOf(item.category)),
                            RoundedCornerShape(2.dp),
                        ),
                )
                Row(
                    Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        // 名称 + 数量：名称溢出时换行（最多 2 行，再长才省略），
                        // 数量是不带 weight 的先测量子项，永远拿到自己的宽度，不会被名称挤掉；
                        // 名称用 fill=false，短名称只占自身宽度，数量紧跟其后而不是被推到最右。
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false),
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            if (item.quantity > 1) {
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "×${item.quantity}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    // 与名称首行基线大致对齐（titleMedium 16sp vs bodyMedium 14sp）
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                        // 备注：紧贴名称下方当副标题，只占一行，多行的数据压成单行后超出用省略号
                        val noteLine = item.note.trim().replace(SINGLE_LINE_WS, " ")
                        if (noteLine.isNotEmpty()) {
                            Spacer(Modifier.height(3.dp))
                            Text(
                                noteLine,
                                style = MaterialTheme.typography.bodySmall,
                                // 比元信息略深一档，读起来是「内容」而不是「标签」
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 空间：只在归属了空间时显示，名称带空间色；未分配不占位置
                            val spaceDef = if (showSpace) store.spaceOf(item.spaceId) else null
                            if (spaceDef != null) {
                                Box(
                                    Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(hexColor(spaceDef.color)),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    spaceDef.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = hexColor(spaceDef.color),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "·",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            // 「N 张图」放到右侧剩余天数下方；数量已挪到名称右边，这里都不再重复
                            val meta = buildList {
                                add(store.categoryLabelOf(item.category))
                                if (item.expireAt.isNotEmpty()) add(item.expireAt)
                            }
                            Text(
                                meta.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 提前提醒档位：如「提前 30/7/1 天」，未设置时不占位
                        remindDaysSummary(item.remindDays)?.let { summary ->
                            Spacer(Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Text(
                                    summary,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                        }
                    }
                    if (selectMode) {
                        Box(
                            Modifier
                                .size(22.dp)
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                    CircleShape,
                                )
                                .border(
                                    1.5.dp,
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Text("✓", color = Color.White, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    } else if (item.expireAt.isNotEmpty() || item.images.isNotEmpty()) {
                        // 右列（自上而下）：剩余天数 → 「张数 + 缩略图」同一行
                        Column(
                            Modifier.padding(start = 8.dp),
                            horizontalAlignment = Alignment.End,
                        ) {
                            if (item.expireAt.isNotEmpty()) {
                                Text(
                                    Dates.describeRemaining(item.expireAt),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = hexColor(status.color),
                                )
                            }
                            if (item.images.isNotEmpty()) {
                                Spacer(Modifier.height(4.dp))
                                // 张数与缩略图同一行：张数在左、缩略图在右，保证缩略图与上方天数同一右边界
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "${item.images.size} 张图",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    // 缩略图：点击直接看大图（多张时在查看器里翻页）
                                    Surface(
                                        onClick = { onViewImages(thumbOrigin) },
                                        modifier = Modifier.onGloballyPositioned { coords ->
                                            val pos = coords.positionInWindow()
                                            thumbOrigin = ImageOrigin(
                                                x = pos.x,
                                                y = pos.y,
                                                width = coords.size.width.toFloat(),
                                                height = coords.size.height.toFloat(),
                                                cornerRadius = thumbRadiusPx,
                                            )
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        border = androidx.compose.foundation.BorderStroke(
                                            1.dp, MaterialTheme.colorScheme.outline,
                                        ),
                                    ) {
                                        DataImage(item.images.first().path, 44.dp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 多选态底部批量操作条：空间 / 分类 / 分享 / 删除。 */
@Composable
private fun SelectionActionBar(
    canAct: Boolean,
    onSpace: () -> Unit,
    onCategory: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionAction("空间", canAct, onSpace)
            SelectionAction("分类", canAct, onCategory)
            SelectionAction("分享", canAct, onShare)
            SelectionAction("删除", canAct, onDelete, danger = true)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SelectionAction(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.weight(1f)) {
        Text(
            text,
            color = when {
                !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                danger -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.primary
            },
        )
    }
}

/**
 * 多选批量改空间。
 * 列表只给「已有空间 + 未分配」，与单据的空间语义一致；要新建空间去「管理空间」。
 */
@Composable
private fun BatchSpaceDialog(count: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.spaceOptions() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移动 $count 件到") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (space in options) {
                    PickRow(space.label, space.color) { onPick(space.id) }
                }
                PickRow(
                    Spaces.UNASSIGNED_LABEL,
                    Spaces.UNASSIGNED_COLOR,
                ) { onPick("") }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 多选批量改分类。 */
@Composable
private fun BatchCategoryDialog(count: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.categoryOptions() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改为分类（$count 件）") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (option in options) {
                    PickRow(option.label, option.color) { onPick(option.id) }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 按空间分享：选空间 → 把该空间的物品整体带给传输页。 */
@Composable
private fun ShareSpaceDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val store = ExpiryStore.get()
    val state by store.state.collectAsStateWithLifecycle()
    val options = remember(state) { store.spaceOptions() }
    // 只算未归档的：与「发送」实际带走的条数一致
    val total = store.itemsInSpace(Spaces.ALL).size
    val unassigned = store.itemsInSpace("").size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("按空间分享") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 340.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "选一个空间，把它下面的物品整体发到对方设备",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                if (total == 0) {
                    Text(
                        "还没有任何物品可以分享",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (space in options) {
                    val count = store.itemsInSpace(space.id).size
                    if (count > 0) {
                        PickRow(space.label, space.color, trailing = "$count 件") { onPick(space.id) }
                    }
                }
                if (unassigned > 0) {
                    PickRow(
                        Spaces.UNASSIGNED_LABEL,
                        Spaces.UNASSIGNED_COLOR,
                        trailing = "$unassigned 件",
                    ) { onPick("") }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 弹窗里的可点选行：颜色点 + 名称 + 可选右侧计数。 */
@Composable
private fun PickRow(
    label: String,
    color: String,
    trailing: String? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(hexColor(color), RoundedCornerShape(5.dp)))
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 从 Compose 的 Context 向上找 Activity（可能被 ContextThemeWrapper 包裹）。 */
private fun Context.findActivity(): Activity? {
    var ctx: Context = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}
