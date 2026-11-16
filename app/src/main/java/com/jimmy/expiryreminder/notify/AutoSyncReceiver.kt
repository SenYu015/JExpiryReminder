package com.jimmy.expiryreminder.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jimmy.expiryreminder.core.AutoSyncEngine
import com.jimmy.expiryreminder.core.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 自动同步的定时兜底：进程被系统回收后，AlarmManager 每 30 分钟拉起一次，
 * 由 App.onCreate 重新拉起引擎，这里再主动做一轮同步并续排下一次。
 */
class AutoSyncReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AutoSyncEngine.ACTION_SYNC) return
        if (!SettingsStore.get().autoSyncEnabled.value) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                AutoSyncEngine.syncNow(app)
                // 续排同样走总闸：期间用户关掉「开机自动同步」时这里会改成取消，不再续排
                AutoSyncEngine.scheduleIfEnabled(app)
            } finally {
                pending.finish()
            }
        }
    }
}
