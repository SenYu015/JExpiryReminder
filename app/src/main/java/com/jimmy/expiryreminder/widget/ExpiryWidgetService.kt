package com.jimmy.expiryreminder.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.jimmy.expiryreminder.MainActivity
import com.jimmy.expiryreminder.R

/** 给小组件的 ListView 提供数据；列表滑动由启动器原生处理，不需要额外权限。 */
class ExpiryWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory =
        Factory(
            applicationContext,
            intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID),
            intent.getStringExtra(ExpiryWidgetBase.EXTRA_MODE) ?: WidgetPrefs.MODE_COUNTDOWN,
        )

    private class Factory(
        private val context: Context,
        @Suppress("UNUSED_PARAMETER") private val widgetId: Int,
        private val mode: String,
    ) : RemoteViewsService.RemoteViewsFactory {

        private var items: List<WidgetItem> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            // 跟随 adapter intent 传入的模式取对应列表；手动选择的持久化由 Provider 负责
            items = WidgetData.load(context).rowsOf(mode)
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
