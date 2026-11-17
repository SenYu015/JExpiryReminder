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
)

object WidgetData {
    private const val MAX_ROWS = 60

    fun load(context: Context): List<WidgetItem> {
        val file = ImageStore.dataFile(context)
        if (!file.exists()) return emptyList()
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return emptyList()
        val arr = root.optJSONArray("items") ?: return emptyList()
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
        return items.sortedWith(compareBy({ it.expireAt.ifBlank { "9999-12-31" } }, { it.name }))
            .take(MAX_ROWS)
            .map { item ->
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
                WidgetItem(
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
                )
            }
    }
}

/**
 * 桌面小组件基类：标准 Android AppWidget（ColorOS 万能卡片/原子组件按此协议适配）。
 * 列表用 RemoteViews + ListView，由启动器原生支持上下滑动。
 */
abstract class ExpiryWidgetBase : AppWidgetProvider() {

    protected abstract val layoutRes: Int

    /** 计数右侧标签：2×2 宽度最紧，只能放两个字。 */
    protected open val countLabel: String = "到期列表"

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
        if (intent.action == ACTION_REFRESH) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
                ?: mgr.getAppWidgetIds(ComponentName(context, this::class.java))
            for (id in ids) updateOne(context, mgr, id)
        }
    }

    @Suppress("DEPRECATION")
    private fun updateOne(context: Context, mgr: AppWidgetManager, id: Int) {
        val views = RemoteViews(context.packageName, layoutRes)
        val count = WidgetData.load(context).size
        val dark = WidgetTheme.isDark(context)
        val palette = WidgetTheme.palette(dark)
        views.setTextViewText(R.id.widget_count, count.toString())
        views.setTextViewText(R.id.widget_count_label, countLabel)
        views.setTextColor(R.id.widget_count, palette.title)
        views.setTextColor(R.id.widget_count_label, palette.accent)
        views.setTextColor(R.id.widget_empty, palette.muted)
        views.setOnClickPendingIntent(R.id.widget_add, addPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_camera, cameraPendingIntent(context))
        views.setOnClickPendingIntent(R.id.widget_count, launchAppPendingIntent(context))
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

        // 每个 widget 实例用独立 data URI，避免多个小组件共用一个适配器
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

    /** 点标题/数字打开应用。 */
    private fun launchAppPendingIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP,
            ),
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

        /** App 数据变化时调用：让三个尺寸的小组件一起刷新。 */
        fun refreshAll(context: Context) {
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
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
                )
            }
        }
    }
}

/** 2×2 紧凑：只显示最紧急的几条。 */
class ExpiryWidgetSmall : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_small
    override val countLabel: String = "到期"
}

/** 4×2 中等：一屏能看更多条目。 */
class ExpiryWidgetMedium : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_medium
}

/** 4×4 大：完整清单 + 状态说明。 */
class ExpiryWidgetLarge : ExpiryWidgetBase() {
    override val layoutRes: Int = R.layout.widget_large
}
