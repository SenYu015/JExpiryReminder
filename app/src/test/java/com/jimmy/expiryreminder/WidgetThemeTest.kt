package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.ExpiryStatus
import com.jimmy.expiryreminder.widget.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WidgetThemeTest {

    @Test
    fun `浅色与深色使用不同卡片底色`() {
        assertEquals(0xFFFFFFFF.toInt(), WidgetTheme.palette(false).card)
        assertEquals(0xFF1A1D21.toInt(), WidgetTheme.palette(true).card)
        assertNotEquals(WidgetTheme.palette(false).title, WidgetTheme.palette(true).title)
    }

    @Test
    fun `行底色与状态色按主题切换`() {
        for (status in ExpiryStatus.entries) {
            assertNotEquals(
                WidgetTheme.rowBg(status, false),
                WidgetTheme.rowBg(status, true),
            )
            assertNotEquals(
                WidgetTheme.statusHex(status, false),
                WidgetTheme.statusHex(status, true),
            )
        }
    }
}
