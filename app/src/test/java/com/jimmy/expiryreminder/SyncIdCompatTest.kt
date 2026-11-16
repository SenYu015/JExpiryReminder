package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.DATA_VERSION
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import org.json.JSONObject
import org.junit.Assert.assertEquals
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
    fun `数据版本已升到 v10`() {
        assertEquals(10, DATA_VERSION)
    }
}
