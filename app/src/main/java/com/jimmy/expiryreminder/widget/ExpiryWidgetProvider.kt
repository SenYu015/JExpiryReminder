package com.jimmy.expiryreminder.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R
import com.jimmy.expiryreminder.core.Categories
import com.jimmy.expiryreminder.core.CustomCategory
import com.jimmy.expiryreminder.core.Dates
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.Spaces
import org.json.JSONObject

/** 小组件列表的一行数据（只读本地 data.json，不联网、不申请权限）。 */
data class WidgetItem(
    val id: String,
    val name: String,
    val expireAt: String,
    val daysText: String,
    /** 「到期 yyyy-MM-dd」右侧第二行。 */
    val expireText: String,
    /** 「空间 · 添加日期」二合一副标题。 */
    val subText: String,
    /** 左侧色条：分类色，与软件内列表卡片的色条同一个来源。 */
    val barColor: Int,
    /** 剩余天数字：到期状态色。 */
    val statusColor: Int,
    val rowBg: Int,
    /** 循环任务标记：列表分流用。 */
    val repeatEnabled: Boolean,
    /** 距到期天数：自动决策「哪一类更临期」用，Int.MAX_VALUE=未设置日期。 */
    val days: Int,
)

/** 一次读取 data.json 后的完整快照：两类的行数据 + 各自的条数与最临期天数。 */
data class WidgetSnapshot(
    val countdown: List<WidgetItem>,
    val cycle: List<WidgetItem>,
    val countdownMinDays: Int,
    val cycleMinDays: Int,
    /** 物品内容指纹（各条 id+updatedAt+rev 的汇总），作为「数据是否变化」的依据。 */
    val dataStamp: String,
) {
    /** 条数（标题显示用）。 */
    fun countOf(mode: String): Int = if (mode == WidgetPrefs.MODE_CYCLE) cycle.size else countdown.size

    /** 按模式取要显示的行。 */
    fun rowsOf(mode: String): List<WidgetItem> =
        if (mode == WidgetPrefs.MODE_CYCLE) cycle else countdown

    /**
     * 自动决策：选出「最临期」的一类。
     * 比较两类最近到期日的天数（含已过期的负数，越紧急），相等时优先倒计时。
     * 空的一类记 Int.MAX_VALUE，自然不会被选中；两类都空时默认倒计时。
     */
    val autoMode: String
        get() = autoModeOf(countdownMinDays, cycleMinDays)

    companion object {
        /** 纯函数：给定两类最临期天数，返回应展示的模式。便于单测。 */
        fun autoModeOf(countdownMinDays: Int, cycleMinDays: Int): String =
            if (cycleMinDays < countdownMinDays) WidgetPrefs.MODE_CYCLE
            else WidgetPrefs.MODE_COUNTDOWN
    }
}

object WidgetData {
    private const val MAX_ROWS = 60

    fun load(context: Context): WidgetSnapshot {
        val file = ImageStore.dataFile(context)
        if (!file.exists()) {
            return WidgetSnapshot(emptyList(), emptyList(), Int.MAX_VALUE, Int.MAX_VALUE, "")
        }
        val root = runCatching { JSONObject(file.readText()) }.getOrNull()
            ?: return WidgetSnapshot(emptyList(), emptyList(), Int.MAX_VALUE, Int.MAX_VALUE, "")
        val arr = root.optJSONArray("items")
            ?: return WidgetSnapshot(emptyList(), emptyList(), Int.MAX_VALUE, Int.MAX_VALUE, "")
        val dark = WidgetTheme.isDark(context)

        // 分类色条必须和软件内部一致：内置分类颜色 + 用户改色覆盖 + 自定义分类颜色。
        // 不能用到期状态色代替，状态色只用于行底色与剩余天数。
        val overrides = HashMap<String, String>()
        root.optJSONObject("categoryColors")?.let { obj ->
            for (key in obj.keys()) overrides[key] = obj.optString(key)
        }
        val categoryColors = Categories.BUILTIN
            .associate { it.id to (overrides[it.id] ?: it.color) }
            .toMutableMap()
        root.optJSONArray("categories")?.let { cats ->
            for (i in 0 until cats.length()) {
                CustomCategory.fromJson(cats.opt(i))?.let { categoryColors[it.id] = it.color }
            }
        }

        // 空间名同步显示，未分配也要明确写出来
        val spaceLabels = HashMap<String, String>()
        root.optJSONArray("spaces")?.let { spaces ->
            for (i in 0 until spaces.length()) {
                val obj = spaces.optJSONObject(i) ?: continue
                val id = obj.optString("id")
                val label = obj.optString("label")
                if (id.isNotEmpty() && label.isNotEmpty()) spaceLabels[id] = label
            }
        }

        val items = ArrayList<ExpiryItem>()
        for (i in 0 until arr.length()) {
            ExpiryItem.fromJson(arr.opt(i))?.let { if (!it.archived) items += it }
        }
        // 按到期日排序，两类各自取前 MAX_ROWS 条；同时统计各自最临期天数（列表首条即最小）
        val sorted = items.sortedWith(compareBy({ it.expireAt.ifBlank { "9999-12-31" } }, { it.name }))
        val countdown = ArrayList<WidgetItem>()
        val cycle = ArrayList<WidgetItem>()
        for (item in sorted) {
            val days = if (item.expireAt.isBlank()) Int.MAX_VALUE
                else (runCatching { Dates.daysUntil(item.expireAt) }.getOrNull() ?: Int.MAX_VALUE)
            val status = when {
                days == Int.MAX_VALUE -> ExpiryStatus.normal
                days < 0 -> ExpiryStatus.expired
                days <= 7 -> ExpiryStatus.soon
                days <= 30 -> ExpiryStatus.month
                else -> ExpiryStatus.normal
            }
            val addedText = runCatching {
                val date = java.time.LocalDate.parse(item.createdAt.take(10))
                "${date.monthValue}月${date.dayOfMonth}日添加"
            }.getOrDefault("")
            val spaceText = if (item.spaceId.isBlank()) Spaces.UNASSIGNED_LABEL
            else spaceLabels[item.spaceId] ?: Spaces.UNASSIGNED_LABEL
            val subText = listOf(spaceText, addedText)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            val rowBg = WidgetTheme.rowBg(status, dark)
            val row = WidgetItem(
                id = item.id,
                name = item.name,
                expireAt = item.expireAt,
                daysText = if (item.expireAt.isBlank()) "未设置"
                else Dates.describeRemaining(item.expireAt),
                expireText = if (item.expireAt.isBlank()) "到期 未设置"
                else if (item.repeatEnabled) "本周期到期 ${item.expireAt}"
                else "到期 ${item.expireAt}",
                subText = subText,
                barColor = runCatching {
                    Color.parseColor(categoryColors[item.category] ?: "#909399")
                }.getOrDefault(Color.GRAY),
                statusColor = runCatching {
                    Color.parseColor(WidgetTheme.statusHex(status, dark))
                }.getOrDefault(Color.GRAY),
                rowBg = rowBg,
                repeatEnabled = item.repeatEnabled,
                days = days,
            )
            // sorted 已按到期日升序，首条即该类最临期；每类只留 MAX_ROWS 条
            val target = if (item.repeatEnabled) cycle else countdown
            if (target.size < MAX_ROWS) target += row
        }
        // 两类都为空列表时，minDays 用 Int.MAX_VALUE，autoMode 自然回落到倒计时
        val countdownMin = countdown.firstOrNull()?.days ?: Int.MAX_VALUE
        val cycleMin = cycle.firstOrNull()?.days ?: Int.MAX_VALUE
        // 内容指纹：只用物品的 id + updatedAt + rev 汇总，不能用 file.lastModified()
        // ——后台同步/原子写会刷新 mtime 但数据没变，会让手动切换选择被误清、看起来「点了没反应」。
        val contentStamp = items.asSequence()
            .sortedBy { it.id }
            .joinToString("|") { "${it.id}:${it.updatedAt}:${it.rev}" }
        return WidgetSnapshot(countdown, cycle, countdownMin, cycleMin, contentStamp)
    }
}

/**
 * 桌面小组件基类：标准 Android AppWidget（ColorOS 万能卡片/原子组件按此协议适配）。
 * 列表用 RemoteViews + ListView，由启动器原生支持上下滑动。
 */
abstract class ExpiryWidgetBase : AppWidgetProvider() {

    protected abstract val layoutRes: Int

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) updateOne(context, appWidgetManager, id)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?,
    ) {
        // ColorOS 17 允许自由拖动改尺寸：重新绑定适配器，列表会按新高度显示更多/更少行
        updateOne(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        android.util.Log.d(
            "WidgetToggle",
            "onReceive action=${intent.action} target=${intent.getStringExtra(EXTRA_TARGET_MODE)} " +
                "force=${intent.getStringExtra(EXTRA_FORCE_MODE)}",
        )
        when (intent.action) {
            ACTION_REFRESH -> {
                val mgr = AppWidgetManager.getInstance(context)
                val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                    ?: mgr.getAppWidgetIds(ComponentName(context, this::class.java))
                val forced = intent.getStringExtra(EXTRA_FORCE_MODE)
                for (id in ids) updateOne(context, mgr, id, forced)
            }
            ACTION_TOGGLE -> {
                // 点椭圆标签：直接切到目标模式（broadcast 点击，无界面、不带起应用）
                val target = intent.getStringExtra(EXTRA_TARGET_MODE)
                if (target == WidgetPrefs.MODE_CYCLE || target == WidgetPrefs.MODE_COUNTDOWN) {
                    val snapshot = WidgetData.load(context)
                    WidgetPrefs.setManualMode(context, target, snapshot.dataStamp)
                    // 先直接刷新本实例（被点击的这个尺寸），确保当前屏立即更新；
                    // 再广播刷新其他尺寸，保持三处一致。
                    val selfIds = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                        ?: AppWidgetManager.getInstance(context)
                            .getAppWidgetIds(ComponentName(context, this::class.java))
                    val mgr = AppWidgetManager.getInstance(context)
                    for (id in selfIds) updateOne(context, mgr, id, target)
                    refreshAll(context, forcedMode = target)
                    // ColorOS 启动器对带 setRemoteAdapter 的 RemoteViews 偶发延迟应用：
                    // 列表（notify 通道）秒切、头部高亮要等下一次系统刷新才追上。
                    // 延迟补一刀全量刷新，把头部在亚秒级顶出来。
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        refreshAll(context)
                    }, 800)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateOne(context: Context, mgr: AppWidgetManager, id: Int, forcedMode: String? = null) {
        val views = RemoteViews(context.packageName, layoutRes)
        val snapshot = WidgetData.load(context)
        val mode = forcedMode?.takeIf {
            it == WidgetPrefs.MODE_CYCLE || it == WidgetPrefs.MODE_COUNTDOWN
        } ?: WidgetPrefs.resolveMode(context, snapshot.dataStamp, snapshot.autoMode)
        android.util.Log.d(
            "WidgetToggle",
            "updateOne id=$id forced=$forcedMode mode=$mode stamp=${snapshot.dataStamp.hashCode()} " +
                "cd=${snapshot.countdown.size} cyc=${snapshot.cycle.size}",
        )
        val count = snapshot.countOf(mode)
        val dark = WidgetTheme.isDark(context)
        val palette = WidgetTheme.palette(dark)
        views.setTextViewText(R.id.widget_count, count.toString())
        views.setTextColor(R.id.widget_count, palette.title)
        // 切换椭圆底色跟随深浅主题
        views.setInt(
            R.id.widget_mode_pill,
            "setBackgroundResource",
            if (dark) R.drawable.widget_mode_pill_dark else R.drawable.widget_mode_pill_light,
        )
        // 两个 label：当前模式高亮（主色），另一个置灰；点哪个切到哪个
        val countdownOn = mode == WidgetPrefs.MODE_COUNTDOWN
        views.setTextColor(
            R.id.widget_tab_countdown,
            if (countdownOn) palette.accent else palette.muted,
        )
        views.setTextColor(
            R.id.widget_tab_cycle,
            if (!countdownOn) palette.accent else palette.muted,
        )
        views.setOnClickPendingIntent(
            R.id.widget_tab_countdown,
            togglePendingIntent(context, WidgetPrefs.MODE_COUNTDOWN, 7003),
        )
        views.setOnClickPendingIntent(
            R.id.widget_tab_cycle,
            togglePendingIntent(context, WidgetPrefs.MODE_CYCLE, 7004),
        )
        views.setTextColor(R.id.widget_empty, palette.muted)
        views.setTextViewText(
            R.id.widget_empty,
            if (mode == WidgetPrefs.MODE_CYCLE) "暂无循环任务" else "暂无倒计时物品",
        )
        views.setOnClickPendingIntent(R.id.widget_add, addPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_camera, cameraPendingIntent(context))
        // 相机键：圆形底与图标色都跟随应用主题（图标用 colorFilter 着色，不影响底形）
        views.setInt(R.id.widget_camera, "setBackgroundResource", WidgetTheme.camBg(dark))
        views.setInt(R.id.widget_camera, "setColorFilter", WidgetTheme.camIcon(dark))

        // 不再提供独立背景图/透明度；卡片颜色跟随应用浅色/深色主题
        views.setInt(
            R.id.widget_scrim,
            "setBackgroundColor",
            palette.card,
        )
        views.setViewVisibility(R.id.widget_bg_image, android.view.View.GONE)

        // adapter URI 固定为实例级（不按模式拆分）：ColorOS 启动器对 URI 变更不重建连接，
        // 按模式拆分会让旧模式的残留工厂与新模式工厂互相覆盖列表（「闪一下又弹回」）；
        // 模式由 Factory 每次 onDataSetChanged 动态读 WidgetPrefs，天然与 Provider 一致。
        // 注意：每次都必须携带 setRemoteAdapter——实测 ColorOS 上一次不带 setRemoteAdapter
        // 的更新会把列表的 RemoteViewsService 连接丢掉且不再恢复（列表永久冻结在旧行，
        // notifyAppWidgetViewDataChanged 也被吞），所以「仅首次绑定」的优化不可用。
        val adapter = Intent(context, ExpiryWidgetService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            data = Uri.parse("jexpiry://widget/$id")
        }
        views.setRemoteAdapter(R.id.widget_list, adapter)
        views.setEmptyView(R.id.widget_list, R.id.widget_empty)

        // 列表项点击：fillInIntent 带物品 id，打开后直接进编辑页
        views.setPendingIntentTemplate(
            R.id.widget_list,
            PendingIntent.getActivity(
                context, 1,
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
                ),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            ),
        )
        mgr.updateAppWidget(id, views)
        mgr.notifyAppWidgetViewDataChanged(id, R.id.widget_list)
    }

    /**
     * 点椭圆里的某个 label：发广播带目标模式，由 onReceive 直接切换。
     * 用 getBroadcast 而非 Activity——Activity 中转会在切回桌面时把 MainActivity 一起带起来。
     * requestCode 用不同值区分两个 label，避免 FLAG_UPDATE_CURRENT 互相覆盖 extra。
     */
    private fun togglePendingIntent(context: Context, targetMode: String, requestCode: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context, requestCode,
            Intent(context, this::class.java)
                .setAction(ACTION_TOGGLE)
                .putExtra(EXTRA_TARGET_MODE, targetMode),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 右上角「+」：清空任务栈重开，直接落到新增页。 */
    private fun addPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7001,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_ADD, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** 「+」左侧相机键：落到与主页相机按钮相同的选图（拍照）页。 */
    private fun cameraPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 7002,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_CAMERA, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        /** 应用内数据变化时发的私有刷新广播，不对外暴露。 */
        const val ACTION_REFRESH = "com.jimmy.expiryreminder.action.WIDGET_REFRESH"

        /** 点椭圆标签切换列表的私有广播（无界面、不带起应用）。 */
        const val ACTION_TOGGLE = "com.jimmy.expiryreminder.action.WIDGET_TOGGLE"
        /** 切换中转 Activity 读取：要切到的目标列表模式。 */
        const val EXTRA_TARGET_MODE = "com.jimmy.expiryreminder.extra.WIDGET_TARGET_MODE"

        /**
         * App 数据变化时调用：让三个尺寸的小组件一起刷新。
         * [forcedMode] 非空时（切换列表场景）直接按该模式渲染，
         * 避免刚写入手动模式就刷新时 resolveMode 因时序读不到新值又回退自动。
         */
        fun refreshAll(context: Context, forcedMode: String? = null) {
            val mgr = AppWidgetManager.getInstance(context)
            for (clazz in listOf(
                ExpiryWidgetSmall::class.java,
                ExpiryWidgetMedium::class.java,
                ExpiryWidgetLarge::class.java,
            )) {
                val ids = mgr.getAppWidgetIds(ComponentName(context, clazz))
                if (ids.isEmpty()) continue
                context.sendBroadcast(
                    Intent(context, clazz)
                        .setAction(ACTION_REFRESH)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                        .putExtra(EXTRA_FORCE_MODE, forcedMode),
                )
            }
        }

        /** 刷新广播可选：强制使用的列表模式（切换瞬间绕过 resolveMode）。 */
        const val EXTRA_FORCE_MODE = "com.jimmy.expiryreminder.extra.WIDGET_FORCE_MODE"
    }
}

/** 2×2 紧凑：只显示最紧急的几条。 */
class ExpiryWidgetSmall : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_small
}

/** 4×2 中等：一屏能看更多条目。 */
class ExpiryWidgetMedium : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_medium
}

/** 4×4 大：完整清单 + 状态说明。 */
class ExpiryWidgetLarge : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_large
}
