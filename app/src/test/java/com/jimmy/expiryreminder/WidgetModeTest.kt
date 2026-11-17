package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.widget.WidgetPrefs
import com.jimmy.expiryreminder.widget.WidgetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Test

/** 小组件「倒计时 / 循环任务」自动切换决策的纯函数测试。 */
class WidgetModeTest {

    @Test
    fun `循环任务更临期时切到循环`() {
        assertEquals(
            WidgetPrefs.MODE_CYCLE,
            WidgetSnapshot.autoModeOf(countdownMinDays = 30, cycleMinDays = 3),
        )
    }

    @Test
    fun `倒计时更临期时切到倒计时`() {
        assertEquals(
            WidgetPrefs.MODE_COUNTDOWN,
            WidgetSnapshot.autoModeOf(countdownMinDays = 2, cycleMinDays = 20),
        )
    }

    @Test
    fun `已过期的一类视为最紧急`() {
        // 循环里有已过期（负数），应优先于倒计时里还有 5 天到期的
        assertEquals(
            WidgetPrefs.MODE_CYCLE,
            WidgetSnapshot.autoModeOf(countdownMinDays = 5, cycleMinDays = -2),
        )
    }

    @Test
    fun `循环为空时保持倒计时`() {
        assertEquals(
            WidgetPrefs.MODE_COUNTDOWN,
            WidgetSnapshot.autoModeOf(countdownMinDays = 15, cycleMinDays = Int.MAX_VALUE),
        )
    }

    @Test
    fun `倒计时为空时切到循环`() {
        assertEquals(
            WidgetPrefs.MODE_CYCLE,
            WidgetSnapshot.autoModeOf(countdownMinDays = Int.MAX_VALUE, cycleMinDays = 9),
        )
    }

    @Test
    fun `两类都空或同样临期时优先倒计时`() {
        assertEquals(
            WidgetPrefs.MODE_COUNTDOWN,
            WidgetSnapshot.autoModeOf(Int.MAX_VALUE, Int.MAX_VALUE),
        )
        assertEquals(
            WidgetPrefs.MODE_COUNTDOWN,
            WidgetSnapshot.autoModeOf(countdownMinDays = 7, cycleMinDays = 7),
        )
    }
}
