package com.jimmy.expiryreminder.widget

import android.content.Context
import java.io.File

/**
 * 小组件不再单独保存外观偏好：卡片、文字与行底色完全跟随应用主题。
 * 这里只负责清理旧版本留下的自定义背景图文件。
 */
object WidgetPrefs {

    private const val LEGACY_BG_NAME = "widget_bg.jpg"

    fun clearLegacyBackground(context: Context) {
        runCatching { File(context.filesDir, LEGACY_BG_NAME).delete() }
    }
}
