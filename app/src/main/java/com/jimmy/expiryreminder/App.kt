package com.jimmy.expiryreminder

import android.app.Application
import android.content.Context
import com.jimmy.expiryreminder.core.AutoSyncEngine
import com.jimmy.expiryreminder.core.AutoSyncMerge
import com.jimmy.expiryreminder.core.ConflictStore
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import com.jimmy.expiryreminder.widget.ExpiryWidgetBase
import com.jimmy.expiryreminder.widget.WidgetPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ReminderService.createChannel(this)
        SettingsStore.get().load(this)
        // 自动同步：跟随设置开关常驻设备发现与合并（开关关闭时只是挂一个观察者，不占端口）
        AutoSyncEngine.watch(this)
        // 旧版本的自定义小组件背景图已不再使用，顺手清理；同时按当前主题刷新小组件配色
        WidgetPrefs.clearLegacyBackground(this)
        ExpiryWidgetBase.refreshAll(this)
        appScope.launch {
            val store = ExpiryStore.get()
            store.load(this@App)
            migrateLegacyConflicts(this@App)
            // 启动时同步一次排程（补偿开机接收器没跑、数据变更没同步等情况）
            val settings = SettingsStore.get()
            ReminderService.syncScheduled(
                this@App, store,
                settings.notifyEnabled.value, settings.notifyTime.value,
                settings.useAlarmClock.value,
            )
        }
        // 数据一变就刷新桌面的三个尺寸小组件（只读本地文件，不联网）
        appScope.launch {
            ExpiryStore.get().state.collect { ExpiryWidgetBase.refreshAll(this@App) }
        }
    }

    /**
     * 旧版本用名称相似度制造人工冲突。把旧规则留下的未处理冲突按
     * 「共同唯一 ID + 修改时间新者胜」重算一次并落地，然后清空，避免用户继续看到
     * 已经可以自动判定的「疑似重复」；每个应用安装只执行一次。
     */
    private suspend fun migrateLegacyConflicts(context: Context) {
        val prefs = context.getSharedPreferences("sync_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("v124", false)) return
        ConflictStore.load(context)
        val pending = ConflictStore.conflicts.value
        if (pending.isNotEmpty()) {
            val store = ExpiryStore.get()
            val adds = ArrayList<ExpiryItem>()
            val updates = ArrayList<ExpiryItem>()
            for (conflict in pending) {
                val plan = AutoSyncMerge.plan(store.items, listOf(conflict.incoming), conflict.from)
                adds.addAll(plan.adds)
                updates.addAll(plan.updates)
            }
            store.applyAutoSyncItems(context, adds, updates)
            ConflictStore.clear(context)
        }
        prefs.edit().putBoolean("v124", true).apply()
    }
}
