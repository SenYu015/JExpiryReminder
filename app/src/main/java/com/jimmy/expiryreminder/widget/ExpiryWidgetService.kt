package com.jimmy.expiryreminder.widget

import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R

/** 给小组件的 ListView 提供数据；列表滑动由启动器原生处理，不需要额外权限。 */
class ExpiryWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(applicationContext)

    /**
     * 工厂不缓存模式：部分启动器（ColorOS）对 adapter intent 变更不重建连接，
     * 按模式拆分 data URI 会留下旧模式的残留工厂，与新模式工厂互相覆盖列表。
     * 每次刷新动态读 WidgetPrefs 的当前模式，所有连接渲染结果一致，天然无竞态。
     */
    private class Factory(
        private val context: Context,
    ) : RemoteViewsService.RemoteViewsFactory {

        private var items: List<WidgetItem> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            val snapshot = WidgetData.load(context)
            val mode = WidgetPrefs.resolveMode(context, snapshot.dataStamp, snapshot.autoMode)
            items = snapshot.rowsOf(mode)
            android.util.Log.d("WidgetToggle", "factory onDataSetChanged mode=$mode rows=${items.size}")
        }

        override fun onDestroy() {
            items = emptyList()
        }

        override fun getCount(): Int = items.size

        override fun getViewAt(position: Int): RemoteViews {
            val rv = RemoteViews(context.packageName, R.layout.widget_item)
            val item = items.getOrNull(position) ?: return rv
            val palette = WidgetTheme.palette(WidgetTheme.isDark(context))
            rv.setTextViewText(R.id.widget_item_name, item.name)
            rv.setTextViewText(R.id.widget_item_days, item.daysText)
            rv.setTextViewText(R.id.widget_item_expire, item.expireText)
            rv.setTextViewText(R.id.widget_item_added, item.subText)
            rv.setTextColor(R.id.widget_item_name, palette.name)
            rv.setTextColor(R.id.widget_item_expire, palette.muted)
            rv.setTextColor(R.id.widget_item_added, palette.muted)
            rv.setTextColor(R.id.widget_item_days, item.statusColor)
            // 左侧色条用分类色（与软件内列表一致）；整行底色按到期状态取浅色版本
            rv.setInt(R.id.widget_item_bar, "setColorFilter", item.barColor)
            rv.setInt(R.id.widget_item_root, "setBackgroundResource", item.rowBg)
            rv.setOnClickFillInIntent(
                R.id.widget_item_root,
                Intent().putExtra(MainActivity.EXTRA_OPEN_ITEM_ID, item.id),
            )
            return rv
        }

        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount(): Int = 1
        override fun getItemId(position: Int): Long =
            items.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds(): Boolean = true
    }
}
