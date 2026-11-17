package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.ExpiryImage
import com.jimmy.expiryreminder.core.ExpiryItem
import com.jimmy.expiryreminder.core.ShelfLifeUnit
import com.jimmy.expiryreminder.notify.ReminderService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ReminderPlanTest {

    private fun item(
        id: String,
        expireAt: String,
        remindDays: List<Int> = listOf(30, 7, 1, 0),
        archived: Boolean = false,
        repeatEnabled: Boolean = false,
    ) = ExpiryItem(
        id = id, name = "物品$id", category = "food",
        productionDate = "", shelfLife = 0, shelfLifeUnit = ShelfLifeUnit.day,
        expireAt = expireAt, remindDays = remindDays, quantity = 1, note = "",
        images = emptyList<ExpiryImage>(), notifiedKeys = emptyList(), archived = archived,
        createdAt = "2026-01-01T00:00:00", updatedAt = "2026-01-01T00:00:00",
        repeatEnabled = repeatEnabled,
    )

    @Test
    fun `按档位生成计划并按时间排序`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2026-12-11"), item("b", "2026-11-12")),
            "09:00",
            now,
        )
        // a: 12-11 的 30/7/1/0 档 → 11-11(已过 09:00)、12-04、12-10、12-11
        // b: 11-12 的 30/7/1/0 档 → 10-13(过去)、11-05(过去)、11-11(过去)、11-12
        // 可用的应是 b 的 0 档 + a 的 7/1/0 档
        assertEquals(
            listOf("2026-11-12:b:0", "2026-12-04:a:7", "2026-12-10:a:1", "2026-12-11:a:0"),
            plan.map { it.key },
        )
        assertTrue(plan.zipWithNext().all { (x, y) -> x.at <= y.at })
    }

    @Test
    fun `已过期与归档物品不排`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("expired", "2026-11-01"), item("arch", "2026-12-01", archived = true)),
            "09:00", now,
        )
        assertTrue(plan.isEmpty())
    }

    @Test
    fun `自定义天数照常排程`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2027-01-11", remindDays = listOf(45))), "09:00", now,
        )
        // 2027-01-11 往前 45 天 = 2026-11-27
        assertEquals(listOf("2026-11-27:a:45"), plan.map { it.key })
    }

    @Test
    fun `重复档位去重`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2026-12-11", remindDays = listOf(7, 7, 1))),
            "09:00", now,
        )
        assertEquals(2, plan.size)
    }

    @Test
    fun `通知 id 不冲突`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val items = (1..20).map { item("id$it", "2026-12-01") }
        val plan = ReminderService.buildReminderPlan(items, "09:00", now)
        assertEquals(plan.size, plan.map { it.id }.distinct().size)
    }

    @Test
    fun `notifyTime 非法时回退 0900`() {
        val now = LocalDateTime.of(2026, 11, 11, 10, 0)
        val plan = ReminderService.buildReminderPlan(
            listOf(item("a", "2026-11-12")), "25:99", now,
        )
        // 09:00 的 0 天档 = 11-12 09:00
        assertEquals(LocalDateTime.of(2026, 11, 12, 9, 0), plan.first().at)
    }

    @Test
    fun `前台补发只在推送时刻之后生效`() {
        // 早于推送时刻：交给闹钟，别一开 App 就提前弹
        assertFalse(ReminderService.isPastNotifyTime("09:00", LocalDateTime.of(2026, 11, 11, 8, 59)))
        // 到点及以后：允许补发
        assertTrue(ReminderService.isPastNotifyTime("09:00", LocalDateTime.of(2026, 11, 11, 9, 0)))
        assertTrue(ReminderService.isPastNotifyTime("09:00", LocalDateTime.of(2026, 11, 11, 23, 30)))
        // 非法时刻按 09:00 处理
        assertTrue(ReminderService.isPastNotifyTime("25:99", LocalDateTime.of(2026, 11, 11, 10, 0)))
        assertFalse(ReminderService.isPastNotifyTime("25:99", LocalDateTime.of(2026, 11, 11, 8, 0)))
    }

    // ---- 流体云候选项：循环任务与倒计时物品规则区分 ----

    private fun liveIds(items: List<ExpiryItem>, now: LocalDate): List<String> =
        ReminderService.liveSummaryCandidates(items, now).map { it.first.id }

    @Test
    fun `短周期循环任务未设提醒档位不上流体云`() {
        // 周期 3 天：到期日永远落在 0..7 窗口内，但未设 remindDays 就不该常驻流体云
        val now = LocalDate.of(2026, 11, 17)
        val items = listOf(
            item("r1", "2026-11-19", remindDays = emptyList(), repeatEnabled = true),
            item("r2", "2026-11-17", remindDays = emptyList(), repeatEnabled = true),
        )
        assertTrue(liveIds(items, now).isEmpty())
    }

    @Test
    fun `循环任务设了提醒但当天未命中档位不上流体云`() {
        // 到期日还有 3 天，提醒档位是 1/0 天：当天不该上云
        val now = LocalDate.of(2026, 11, 17)
        val items = listOf(
            item("r1", "2026-11-20", remindDays = listOf(1, 0), repeatEnabled = true),
        )
        assertTrue(liveIds(items, now).isEmpty())
    }

    @Test
    fun `循环任务当天命中提醒档位才上流体云`() {
        val now = LocalDate.of(2026, 11, 17)
        val items = listOf(
            item("hit", "2026-11-20", remindDays = listOf(3, 1, 0), repeatEnabled = true),
            item("r2", "2026-11-19", remindDays = listOf(1, 0), repeatEnabled = true),
        )
        assertEquals(listOf("hit"), liveIds(items, now))
    }

    @Test
    fun `循环任务提醒档位在7天窗口外仍不上流体云`() {
        // 距到期 10 天，即使命中 remindDays=10 也不进 7 天汇总窗口
        val now = LocalDate.of(2026, 11, 17)
        val items = listOf(
            item("r1", "2026-11-27", remindDays = listOf(10, 30), repeatEnabled = true),
        )
        assertTrue(liveIds(items, now).isEmpty())
    }

    @Test
    fun `倒计时物品维持7天窗口且不依赖提醒档位`() {
        val now = LocalDate.of(2026, 11, 17)
        val items = listOf(
            item("n1", "2026-11-20", remindDays = emptyList()),      // 3 天，未设提醒也上云
            item("n2", "2026-11-24", remindDays = listOf(0)),        // 7 天，档位未命中也上云
            item("n3", "2026-11-25", remindDays = listOf(60, 30)),   // 8 天，窗口外不上云
            item("n4", "2026-11-10", remindDays = listOf(0)),        // 已过期，不上云
        )
        assertEquals(listOf("n1", "n2"), liveIds(items, now))
    }

    @Test
    fun `归档与非法日期的循环任务不上流体云`() {
        val now = LocalDate.of(2026, 11, 17)
        val items = listOf(
            item("arch", "2026-11-18", remindDays = listOf(1), repeatEnabled = true, archived = true),
            item("bad", "not-a-date", remindDays = listOf(1), repeatEnabled = true),
        )
        assertTrue(liveIds(items, now).isEmpty())
    }
}
