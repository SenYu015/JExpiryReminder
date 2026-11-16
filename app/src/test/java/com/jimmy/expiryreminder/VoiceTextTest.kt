package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.VoiceText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextTest {

    @Test
    fun `一句话拆出名称到期日与存放位置`() {
        val r = VoiceText.parseQuickRecord("牛奶 有效期至 2027年2月10日 放在冰箱上层")
        assertEquals("2027-02-10", r.expireAt)
        assertTrue("名称应包含牛奶，实际：${r.name}", r.name.contains("牛奶"))
        assertTrue("备注应包含位置，实际：${r.note}", r.note.contains("冰箱上层"))
    }

    @Test
    fun `没有日期时只保留名称`() {
        val r = VoiceText.parseQuickRecord("护照放在抽屉里")
        assertTrue(r.name.contains("护照"))
        assertEquals("", r.expireAt)
        assertTrue(r.note.contains("抽屉里"))
    }

    @Test
    fun `空输入返回空草稿`() {
        val r = VoiceText.parseQuickRecord("   ")
        assertEquals("", r.name)
        assertEquals("", r.expireAt)
        assertEquals("", r.note)
    }
}
