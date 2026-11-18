package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.DATA_VERSION
import com.jimmy.expiryreminder.core.ExpiryData
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.core.Tombstone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncIdCompatTest {

    private fun item(syncId: String) = ExpiryItem(
        id = "i1",
        name = "牛奶",
        category = "food",
        productionDate = "",
        shelfLife = 0,
        shelfLifeUnit = ShelfLifeUnit.month,
        expireAt = "2027-02-10",
        remindDays = listOf(7),
        quantity = 1,
        note = "",
        images = emptyList(),
        notifiedKeys = emptyList(),
        archived = false,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        spaceId = "",
        rev = 3L,
        syncId = syncId,
    )

    @Test
    fun `syncId 随 JSON 往返保持不变`() {
        val json = item("s-unified").toJson()
        assertEquals("s-unified", json.getString("syncId"))
        assertEquals("s-unified", ExpiryItem.fromJson(json)?.syncId)
    }

    @Test
    fun `旧数据缺 syncId 时读为空，由仓库派生补发`() {
        val json = JSONObject(item("").toJson().toString())
        json.remove("syncId")
        val restored = ExpiryItem.fromJson(json)
        assertEquals("", restored?.syncId)
        assertEquals("s_i1", "s_" + restored?.id)
    }

    @Test
    fun `数据版本已升到 v13（含 syncId、分类默认提醒、循环任务标记与删除墓碑）`() {
        // v9 起 data.json 带 rev/revision 时钟，v10 加 syncId，
        // v12 加循环任务标记；v13 加删除墓碑 tombstones（删除动作可同步到另一台设备）
        assertEquals(13, DATA_VERSION)
    }

    @Test
    fun `删除墓碑随 data json 往返保持不变`() {
        val payload = ExpiryData(
            version = DATA_VERSION,
            items = emptyList(),
            categories = emptyList(),
            categoryColors = emptyMap(),
            tombstones = mapOf("i1" to Tombstone("i1", "s_i1", 42L)),
        ).toJson()
        val arr = payload.getJSONArray("tombstones")
        assertEquals(1, arr.length())
        val restored = Tombstone.fromJson(arr.getJSONObject(0))
        assertEquals("i1", restored?.id)
        assertEquals("s_i1", restored?.syncId)
        assertEquals(42L, restored?.rev)
        // 旧数据没有 tombstones 键时不会写坏 JSON，读取端按空处理
        assertTrue(payload.has("tombstones"))
        assertTrue(ExpiryData(version = DATA_VERSION, items = emptyList(), categories = emptyList(), categoryColors = emptyMap()).toJson().getJSONArray("tombstones").length() == 0)
    }

    @Test
    fun `合并后 revision 时钟与分类默认提醒能同时落盘`() {
        val payload = ExpiryData(
            version = DATA_VERSION,
            items = listOf(item("s-unified")),
            categories = emptyList(),
            categoryColors = emptyMap(),
            revision = 7L,
            categoryRemindDefaults = mapOf("food" to listOf(3, 7)),
        ).toJson()
        assertEquals(7L, payload.getLong("revision"))
        val defaults = payload.getJSONObject("categoryRemindDefaults")
        assertEquals(2, defaults.getJSONArray("food").length())
        assertEquals(3, defaults.getJSONArray("food").getInt(0))
        // 单条记录自身的 rev 也不能被分类默认提醒字段挤掉
        assertEquals(3L, payload.getJSONArray("items").getJSONObject(0).getLong("rev"))
    }
}
