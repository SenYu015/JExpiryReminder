package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.AutoSyncMerge
import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.Similarity
import com.jimmy.expiryreminder.core.SyncDelta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncMergeTest {

    private fun item(
        id: String,
        name: String,
        expireAt: String = "2027-02-10",
        updatedAt: String = "2026-01-01T00:00:00Z",
        note: String = "",
        productionDate: String = "",
        shelfLife: Int = 0,
        remindDays: List<Int> = listOf(7),
        rev: Long = 0L,
        syncId: String = "",
    ) = ExpiryItem(
        id = id,
        name = name,
        category = "food",
        productionDate = productionDate,
        shelfLife = shelfLife,
        shelfLifeUnit = ShelfLifeUnit.month,
        expireAt = expireAt,
        remindDays = remindDays,
        quantity = 1,
        note = note,
        images = emptyList(),
        notifiedKeys = emptyList(),
        archived = false,
        createdAt = "2025-12-01T00:00:00Z",
        updatedAt = updatedAt,
        spaceId = "",
        rev = rev,
        syncId = syncId,
    )

    @Test
    fun `名称归一化去掉全角括号与标点`() {
        assertEquals("原味燕麦脆", Similarity.normalizeName(" 原味燕麦脆（袋装） "))
        assertEquals("vitaminc", Similarity.normalizeName("Ｖｉｔａｍｉｎ　Ｃ"))
        assertEquals(1.0, Similarity.score("牛奶", "牛奶"), 0.001)
    }

    @Test
    fun `相似名称得分达到阈值`() {
        assertTrue(Similarity.score("原味燕麦脆", "原味燕麦脆片") >= Similarity.THRESHOLD)
    }

    @Test
    fun `共同唯一 ID 相同但本地 id 不同，按修改时间新者覆盖且不弹冲突`() {
        val local = item("local-1", "牛奶", updatedAt = "2026-01-01T00:00:00Z", rev = 99, syncId = "s-unified")
        val remote = item("remote-9", "纯牛奶", updatedAt = "2026-05-01T00:00:00Z", rev = 1, syncId = "s-unified")
        val plan = AutoSyncMerge.plan(listOf(local), listOf(remote), "设备B")
        assertEquals(1, plan.updates.size)
        assertEquals("local-1", plan.updates[0].id)
        assertEquals("s-unified", plan.updates[0].syncId)
        assertEquals("纯牛奶", plan.updates[0].name)
        assertEquals(0, plan.adds.size)
        assertEquals(0, plan.conflicts.size)
    }

    @Test
    fun `修改时间新者胜，rev 只用于增量水位`() {
        val local = item("a", "本机旧", updatedAt = "2026-01-01T00:00:00Z", rev = 99, syncId = "s1")
        val remote = item("a", "对方新", updatedAt = "2026-06-01T00:00:00Z", rev = 1, syncId = "s1")
        val plan = AutoSyncMerge.plan(listOf(local), listOf(remote), "设备B")
        assertEquals(1, plan.updates.size)
        assertEquals("对方新", plan.updates[0].name)
    }

    @Test
    fun `本机修改时间更新时跳过覆盖并抬 rev 回传`() {
        val local = item("a", "本机新", updatedAt = "2026-08-01T00:00:00Z", rev = 1, syncId = "s1")
        val remote = item("a", "对方旧", updatedAt = "2026-01-01T00:00:00Z", rev = 99, syncId = "s1")
        val plan = AutoSyncMerge.plan(listOf(local), listOf(remote), "设备B")
        assertEquals(0, plan.updates.size)
        assertEquals(0, plan.conflicts.size)
        assertTrue(plan.touchIds.contains("a"))
    }

    @Test
    fun `两台设备各自离线录入同一物品，按业务键自动收敛且不再产生冲突`() {
        val local = item("local", "原味燕麦脆", updatedAt = "2026-01-01T00:00:00Z", rev = 3)
        val remote = item("remote", "原味燕麦脆 ", updatedAt = "2026-04-01T00:00:00Z", rev = 5)
        val plan = AutoSyncMerge.plan(listOf(local), listOf(remote), "设备B")
        assertEquals(0, plan.conflicts.size)
        assertEquals(0, plan.adds.size)
        assertEquals(1, plan.updates.size)
        // 双方都缺 syncId 时按字典序派生出同一个共同唯一 ID
        assertEquals("s_local", plan.updates[0].syncId)
    }

    @Test
    fun `名称相似但到期日不同是两件物品，直接新增且不弹冲突`() {
        val plan = AutoSyncMerge.plan(
            listOf(item("local", "原味燕麦脆", expireAt = "2027-02-10", rev = 3)),
            listOf(item("remote", "原味燕麦脆片", expireAt = "2027-05-10", rev = 4)),
            "设备B",
        )
        assertEquals(0, plan.conflicts.size)
        assertEquals(1, plan.adds.size)
        assertEquals("remote", plan.adds[0].id)
    }

    @Test
    fun `增量筛选只取水位之后的变更`() {
        val items = listOf(item("a", "A", rev = 3), item("b", "B", rev = 8), item("c", "C", rev = 12))
        assertEquals(listOf("b", "c"), SyncDelta.pick(items, 5L).map { it.id })
        assertEquals(12L, SyncDelta.maxRev(items))
        assertEquals(0L, SyncDelta.maxRev(emptyList()))
    }

    @Test
    fun `新物品保留对方 id syncId 与图片引用`() {
        val remote = item("remote", "护照", expireAt = "2028-01-01", rev = 9, syncId = "s-passport").copy(
            images = listOf(
                ExpiryImage("img1", "images/img1.jpg", "2026-01-01T00:00:00Z"),
            ),
        )
        val plan = AutoSyncMerge.plan(
            listOf(item("local", "牛奶", rev = 4)),
            listOf(remote),
            "设备B",
        )
        assertEquals(1, plan.adds.size)
        assertEquals("remote", plan.adds[0].id)
        assertEquals("s-passport", plan.adds[0].syncId)
        assertEquals("images/img1.jpg", plan.adds[0].images[0].path)
    }

    @Test
    fun `旧数据无 syncId 时 stableId 由本地 id 派生`() {
        assertEquals("s_abc", AutoSyncMerge.stableId(item("abc", "牛奶")))
    }

    @Test
    fun `智能合并补齐空字段，取较早到期日并拼接备注`() {
        val local = item(
            "local", "牛奶", expireAt = "2027-06-01", note = "放冰箱",
            remindDays = listOf(7), rev = 4,
        )
        val incoming = item(
            "remote", "牛奶", expireAt = "2027-03-01", note = "第二层",
            productionDate = "2026-03-01", shelfLife = 12, remindDays = listOf(30), rev = 5,
        )
        val merged = AutoSyncMerge.mergeFields(local, incoming)
        assertEquals("2027-03-01", merged.expireAt)
        assertEquals("2026-03-01", merged.productionDate)
        assertEquals(12, merged.shelfLife)
        assertEquals("放冰箱 / 第二层", merged.note)
        assertEquals(listOf(30, 7), merged.remindDays)
    }
}
