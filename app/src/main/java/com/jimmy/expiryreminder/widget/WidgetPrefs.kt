package com.jimmy.expiryreminder.widget

import android.content.Context
import java.io.File

/**
 * 小组件外观完全跟随应用主题；这里负责两件事：
 * 1) 清理旧版本留下的自定义背景图文件；
 * 2) 保存「倒计时 / 循环任务」列表模式（三个尺寸全局共用一份）。
 *
 * 模式策略：默认自动选更临期的一类；用户点标题手动切换后记住选择，
 * 直到 data.json 的数据指纹变化（增删改/同步）才清除手动、重算自动。
 */
object WidgetPrefs {

    private const val LEGACY_BG_NAME = "widget_bg.jpg"

    const val MODE_COUNTDOWN = "countdown"
    const val MODE_CYCLE = "cycle"

    private const val PREFS = "widget_mode_prefs"
    private const val KEY_MODE = "mode"
    private const val KEY_STAMP = "stamp"

    fun clearLegacyBackground(context: Context) {
        runCatching { File(context.filesDir, LEGACY_BG_NAME).delete() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 解析本次应展示的列表：
     * - 无手动选择，或手动时的数据指纹与当前不一致（数据已变化）→ 用自动结果；
     * - 否则沿用手动选择。
     * 数据变化时顺手清掉过期的手动选择，让之后所有刷新都回到自动。
     */
    fun resolveMode(context: Context, dataStamp: String, autoMode: String): String {
        val p = prefs(context)
        val mode = p.getString(KEY_MODE, "").orEmpty()
        if (mode.isEmpty()) return autoMode
        // 2.0.2 曾用 putLong 存指纹，升级到 2.0.3 后 getString 会抛 ClassCastException；
        // 判断实际类型，旧格式或非字符串一律清掉、回退自动，保证升级不崩溃。
        val savedStamp = runCatching { p.all[KEY_STAMP] as? String }.getOrNull()
        if (savedStamp == null || savedStamp != dataStamp) {
            p.edit().remove(KEY_MODE).remove(KEY_STAMP).apply()
            return autoMode
        }
        return mode
    }

    /** 用户点标题手动切到指定模式，并记录此刻的数据指纹。 */
    fun setManualMode(context: Context, mode: String, dataStamp: String) {
        prefs(context).edit()
            .putString(KEY_MODE, mode)
            .putString(KEY_STAMP, dataStamp)
            .apply()
    }
}
