package com.jimmy.expiryreminder

import android.app.Application
import android.content.Context
import com.jimmy.expiryreminder.core.AutoSyncEngine
import com.jimmy.expiryreminder.core.AutoSyncMerge
import com.jimmy.expiryreminder.core.ConflictStore
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ExpiryStore
import com.jimmy.expiryreminder.core.ImageStore
import com.jimmy.expiryreminder.core.SettingsStore
import com.jimmy.expiryreminder.notify.ReminderService
import com.jimmy.expiryreminder.widget.ExpiryWidgetBase
import com.jimmy.expiryreminder.widget.WidgetPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class App : Application() {

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        ReminderService.createChannel(this)
        SettingsStore.get().load(this)
        // 旧版本的自定义小组件背景图已不再使用，顺手清理；同时按当前主题刷新小组件配色
        WidgetPrefs.clearLegacyBackground(this)
        ExpiryWidgetBase.refreshAll(this)
        // 语音输入已移除：旧版本把 66MB 的 Vosk 模型解压到 files/vosk-cn，
        // 升级安装不会自动删文件，这里清一次释放空间（目录不存在时是无操作）
        appScope.launch { File(filesDir, "vosk-cn").deleteRecursively() }
        // 想法收件箱 / AI 整理 / 语音转写已独立成另一个应用（v3.0.12 起本应用不再包含）：
        // 清理旧版本留下的体积与隐私残留（录音、离线 ASR 模型、热词表、API Key）
        appScope.launch { purgeLegacyInboxAudio() }
        // 自动同步：跟随设置开关常驻发现与合并（开关关闭时只是挂一个观察者，不占端口）
        AutoSyncEngine.watch(this)
        // 数据一变就刷新桌面的三个尺寸小组件（只读本地文件，不联网）
        appScope.launch {
            ExpiryStore.get().state.collect { ExpiryWidgetBase.refreshAll(this@App) }
        }
        appScope.launch {
            val store = ExpiryStore.get()
            store.load(this@App)
            migrateLegacyConflicts(this@App)
            // 启动时同步一次排程（补偿开机接收器没跑、数据变更没同步等情况）
            val settings = SettingsStore.get()
            ReminderService.syncScheduled(
                this@App, store,
                settings.notifyEnabled.value, settings.notifyTime.value,
                settings.useAlarmClock.value, settings.liveUpdateEnabled.value,
            )
        }
    }

    /**
     * 一次性清理旧版本「想法 / AI / 语音」留下的残留：
     * - `modules/inbox` 下的录音 wav 与 audio 目录（文字条目 inbox.json 保留）
     * - `JExpiryReminder/asr`（运行期拷贝的 SenseVoice 模型，约 230MB）与热词表
     * - `ai_settings` 里保存的 LLM API Key 密文（应用已不再使用，一并清掉）
     * 各步都容错，失败不影响启动。
     */
    private fun purgeLegacyInboxAudio() {
        val prefs = getSharedPreferences("inbox_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("legacy_purged_v3014", false)) return

        // 1) 录音（两个候选根目录都扫，兼容历史布局）
        val inboxRoots = listOf(
            File(File(filesDir, ImageStore.APP_DIR_NAME), "modules/inbox"),
            File(filesDir, "modules/inbox"),
        )
        for (root in inboxRoots) {
            if (!root.exists()) continue
            root.walkTopDown()
                .filter { it.isFile && it.extension.equals("wav", true) }
                .forEach { runCatching { it.delete() } }
            runCatching { File(root, "audio").deleteRecursively() }
        }

        // 2) 离线 ASR 模型与热词表（APK 里已不再打包，运行期拷贝的这份也要清）
        val appRoot = File(filesDir, ImageStore.APP_DIR_NAME)
        runCatching { File(appRoot, "asr").deleteRecursively() }
        runCatching { File(appRoot, "hotwords.txt").delete() }
        runCatching { File(appRoot, "hotwords.sherpa.txt").delete() }

        // 3) API Key 密文
        runCatching {
            getSharedPreferences("ai_settings", Context.MODE_PRIVATE).edit().clear().commit()
        }

        prefs.edit().putBoolean("legacy_purged_v3014", true).apply()
    }

    /**
     * v1.24.0 起不再用名称相似度制造人工冲突。把旧规则留下的未处理冲突按
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
