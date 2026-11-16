package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.CustomCategory
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.MetaSnapshot
import com.jimmy.expiryreminder.core.MetaSync
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.SpaceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaSyncTest {

    private fun cat(id: String, label: String, color: String = "#67C23A") =
        CustomCategory(id, label, color, "2026-01-01T00:00:00Z")

    private fun space(id: String, label: String, color: String = "#409EFF") =
        SpaceInfo(id, label, color, "2026-01-01T00:00:00Z")

    private fun item(id: String, category: String, spaceId: String) = ExpiryItem(
        id = id,
        name = "物品$id",
        category = category,
        productionDate = "",
        shelfLife = 0,
        shelfLifeUnit = ShelfLifeUnit.month,
        expireAt = "2027-01-01",
        remindDays = listOf(7),
        quantity = 1,
        note = "",
        images = emptyList(),
        notifiedKeys = emptyList(),
        archived = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        spaceId = spaceId,
    )

    @Test
    fun `同 id 分类保留本机，远端 id 映射到本机`() {
        val local = MetaSnapshot(categories = listOf(cat("c1", "零食", "#111111")))
        val remote = MetaSnapshot(categories = listOf(cat("c1", "零食改名", "#222222")))
        val plan = MetaSync.plan(local, remote)
        assertTrue(plan.addCategories.isEmpty())
        assertEquals("c1", plan.categoryIdMap["c1"])
        assertTrue(plan.categoryColors.isEmpty())
    }

    @Test
    fun `同名不同 id 映射到本机，不产生重复分类`() {
        val local = MetaSnapshot(categories = listOf(cat("local-1", "爸妈家")))
        val remote = MetaSnapshot(categories = listOf(cat("remote-9", " 爸妈家 ")))
        val plan = MetaSync.plan(local, remote)
        assertTrue(plan.addCategories.isEmpty())
        assertEquals("local-1", plan.categoryIdMap["remote-9"])
    }

    @Test
    fun `远端独有分类与空间会新增，并映射自身`() {
        val local = MetaSnapshot(categories = listOf(cat("c1", "食品")), spaces = emptyList())
        val remote = MetaSnapshot(
            categories = listOf(cat("c2", "宠物")),
            spaces = listOf(space("sp1", "公司")),
        )
        val plan = MetaSync.plan(local, remote)
        assertEquals(1, plan.addCategories.size)
        assertEquals("c2", plan.addCategories[0].id)
        assertEquals("c2", plan.categoryIdMap["c2"])
        assertEquals(1, plan.addSpaces.size)
        assertEquals("sp1", plan.spaceIdMap["sp1"])
        assertTrue(plan.categoryOrder.contains("c2"))
    }

    @Test
    fun `物品引用会按映射改成本机 id`() {
        val plan = MetaSync.plan(
            MetaSnapshot(categories = listOf(cat("local-c", "药品"))),
            MetaSnapshot(
                categories = listOf(cat("remote-c", "药品")),
                spaces = listOf(space("remote-sp", "公司")),
            ),
        )
        val remapped = MetaSync.remap(
            listOf(item("i1", "remote-c", "remote-sp"), item("i2", "medicine", "")),
            plan,
        )
        assertEquals("local-c", remapped[0].category)
        assertEquals("remote-sp", remapped[0].spaceId)
        // 内置分类 id 不在映射里，保持原样
        assertEquals("medicine", remapped[1].category)
    }

    @Test
    fun `颜色本机优先，缺失键从远端补`() {
        val local = MetaSnapshot(
            categoryColors = mapOf("food" to "#111111"),
            spaceColors = mapOf("__all__" to "#222222"),
        )
        val remote = MetaSnapshot(
            categoryColors = mapOf("food" to "#999999", "medicine" to "#F56C6C"),
            spaceColors = mapOf("__all__" to "#888888", "__unassigned__" to "#909399"),
        )
        val plan = MetaSync.plan(local, remote)
        assertEquals("#111111", plan.categoryColors["food"])
        assertEquals("#F56C6C", plan.categoryColors["medicine"])
        assertEquals("#222222", plan.spaceColors["__all__"])
        assertEquals("#909399", plan.spaceColors["__unassigned__"])
    }

    @Test
    fun `改名覆盖本机优先，远端补充缺失`() {
        val local = MetaSnapshot(categoryLabels = mapOf("food" to "吃的"))
        val remote = MetaSnapshot(categoryLabels = mapOf("food" to "食品改名", "medicine" to "药"))
        val plan = MetaSync.plan(local, remote)
        assertEquals("吃的", plan.categoryLabels["food"])
        assertEquals("药", plan.categoryLabels["medicine"])
    }
}
