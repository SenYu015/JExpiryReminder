package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.NameSimilarity
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 名称相似度：新增物品时的「可能已录过」提示。 */
class NameSimilarityTest {

    private fun item(
        name: String,
        id: String = "i_$name",
        expireAt: String = "2026-12-31",
        archived: Boolean = false,
    ) = ExpiryItem(
        id = id, name = name, category = "food",
        productionDate = "2026-10-01", shelfLife = 60, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = expireAt, remindDays = listOf(7), quantity = 1, note = "",
        images = emptyList(), notifiedKeys = emptyList(), archived = archived,
        createdAt = "2026-10-01T00:00:00", updatedAt = "2026-10-01T00:00:00",
    )

    // ---------------------------------------------------------------- 归一化

    @Test
    fun `归一化去掉空格标点并统一全半角大小写`() {
        assertEquals("维生素c片", NameSimilarity.normalize(" 维生素 Ｃ片 "))
        assertEquals("伊利牛奶", NameSimilarity.normalize("伊利、牛奶。"))
        assertEquals("", NameSimilarity.normalize("—— 、"))
    }

    @Test
    fun `只差空格标点的名称视为完全相同`() {
        assertEquals(1.0, NameSimilarity.score("伊利 牛奶", "伊利牛奶、"), 0.0001)
    }

    // ---------------------------------------------------------------- 打分

    @Test
    fun `完全相同得满分`() {
        assertEquals(1.0, NameSimilarity.score("维生素C", "维生素C"), 0.0001)
    }

    @Test
    fun `短名被长名包含时高分`() {
        assertTrue(NameSimilarity.score("牛奶", "伊利纯牛奶") >= 0.85)
        assertTrue(NameSimilarity.score("洗面奶", "洗面奶500ml") >= 0.85)
    }

    @Test
    fun `同款不同规格靠字符重合度命中`() {
        assertTrue(NameSimilarity.score("维生素C", "维生素B") >= NameSimilarity.MIN_SCORE)
    }

    @Test
    fun `毫不相干的名称不打分`() {
        assertEquals(0.0, NameSimilarity.score("牛奶", "护照"), 0.0001)
        assertEquals(0.0, NameSimilarity.score("口罩", "面膜"), 0.0001)
    }

    @Test
    fun `单个字符不参与包含判断避免噪音`() {
        assertTrue(NameSimilarity.score("药", "感冒药") < NameSimilarity.MIN_SCORE)
    }

    // ---------------------------------------------------------------- 检索

    @Test
    fun `寻回相似物品并把同名的排在最前`() {
        val items = listOf(
            item("伊利纯牛奶", id = "a"),
            item("牛奶", id = "b"),
            item("护照", id = "c"),
        )
        val result = NameSimilarity.findSimilar(items, "牛奶")
        assertEquals(2, result.size)
        assertEquals("b", result.first().item.id)
        assertTrue(result.first().sameName)
        assertFalse(result[1].sameName)
    }

    @Test
    fun `查询串太短直接返回空`() {
        val items = listOf(item("感冒药"), item("眼药水"))
        assertTrue(NameSimilarity.findSimilar(items, "").isEmpty())
        assertTrue(NameSimilarity.findSimilar(items, " ").isEmpty())
        assertTrue(NameSimilarity.findSimilar(items, "药").isEmpty())
    }

    @Test
    fun `已归档物品与自身不参与检索`() {
        val items = listOf(
            item("牛奶", id = "live"),
            item("牛奶", id = "gone", archived = true),
        )
        assertEquals(listOf("live"), NameSimilarity.findSimilar(items, "牛奶").map { it.item.id })
        assertTrue(NameSimilarity.findSimilar(items, "牛奶", excludeId = "live").isEmpty())
    }

    @Test
    fun `同分时到期日早的排前面`() {
        val items = listOf(
            item("伊利牛奶", id = "late", expireAt = "2027-03-01"),
            item("蒙牛牛奶", id = "soon", expireAt = "2026-11-20"),
            item("光明牛奶", id = "none", expireAt = ""),
        )
        val ids = NameSimilarity.findSimilar(items, "牛奶").map { it.item.id }
        assertEquals(listOf("soon", "late", "none"), ids)
    }

    @Test
    fun `结果条数受 limit 限制`() {
        val items = (1..20).map { item("纯牛奶$it", id = "i$it") }
        assertEquals(12, NameSimilarity.findSimilar(items, "纯牛奶").size)
        assertEquals(3, NameSimilarity.findSimilar(items, "纯牛奶", limit = 3).size)
    }
}
