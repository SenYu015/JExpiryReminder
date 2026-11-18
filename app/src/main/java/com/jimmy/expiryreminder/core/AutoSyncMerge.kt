package com.jimmy.expiryreminder.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 一条待人工处理的冲突：本机物品 vs 另一台设备发来的疑似同一件物品。 */
data class PendingConflict(
    val key: String,
    val local: ExpiryItem,
    val incoming: ExpiryItem,
    val score: Double,
    val reason: String,
    val from: String,
    val detectedAt: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("key", key)
        .put("score", score)
        .put("reason", reason)
        .put("from", from)
        .put("detectedAt", detectedAt)
        .put("local", local.toJson())
        .put("incoming", incoming.toJson())

    companion object {
        fun fromJson(raw: Any?): PendingConflict? {
            if (raw !is JSONObject) return null
            val local = ExpiryItem.fromJson(raw.opt("local")) ?: return null
            val incoming = ExpiryItem.fromJson(raw.opt("incoming")) ?: return null
            val key = raw.optStringOrNull("key")?.takeIf { it.isNotBlank() }
                ?: (local.id + "|" + Similarity.fingerprint(incoming))
            return PendingConflict(
                key = key,
                local = local,
                incoming = incoming,
                score = raw.optDouble("score", 0.0),
                reason = raw.optStringOrNull("reason") ?: "疑似同一物品",
                from = raw.optStringOrNull("from") ?: "另一台设备",
                detectedAt = raw.optStringOrNull("detectedAt") ?: Dates.nowIsoTimestamp(),
            )
        }
    }
}

/** 一次自动同步的落地计划：只增改、不删除。 */
data class SyncPlan(
    /** 直接新增（保留对方 id / syncId / 图片引用）。 */
    val adds: List<ExpiryItem> = emptyList(),
    /** 覆盖本机记录（保留本机 id / createdAt / images，updatedAt 保持胜者值）。 */
    val updates: List<ExpiryItem> = emptyList(),
    /** 仅在无法自动判定新旧时才有值；当前规则下基本为空。 */
    val conflicts: List<PendingConflict> = emptyList(),
    val skipped: Int = 0,
    /** 本机较新，需要抬 rev 回传给对端的本机 id。 */
    val touchIds: List<String> = emptyList(),
) {
    val changed: Int get() = adds.size + updates.size
}

object AutoSyncMerge {

    /**
     * 稳定业务键：归一化名称 + 到期日 + 数量。
     * 两台设备离线各自录入同一件物品时，也靠它对齐到同一个共同唯一 ID。
     */
    fun syncKey(item: ExpiryItem): String =
        Similarity.normalizeName(item.name) + "|" + item.expireAt.trim() + "|" + item.quantity

    /** 共同唯一 ID：优先持久 syncId；旧数据用 id 派生，保证非空且稳定。 */
    fun stableId(item: ExpiryItem): String = item.syncId.ifBlank { "s_" + item.id }

    /** 两份副本收敛到同一个 syncId：优先真实 syncId 的较小值，保证两端算法一致。 */
    private fun canonicalId(a: ExpiryItem, b: ExpiryItem): String {
        val real = listOf(a.syncId, b.syncId).filter { it.isNotBlank() }.sorted()
        if (real.isNotEmpty()) return real.first()
        return "s_" + minOf(a.id, b.id)
    }

    /** 统一按 UTC 解析，避免两台设备时区不同导致「谁更新」判断不一致。 */
    private fun timestamp(raw: String): Long {
        if (raw.isBlank()) return 0L
        return runCatching {
            java.time.LocalDateTime.parse(raw)
                .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }.recoverCatching {
            java.time.Instant.parse(raw).toEpochMilli()
        }.getOrDefault(0L)
    }

    /** 业务内容指纹：排除 id / syncId / 时间戳 / 通知记录 / 图片等本地字段。 */
    private fun contentOf(item: ExpiryItem): String = item.copy(
        id = "", syncId = "", rev = 0L, updatedAt = "", createdAt = "",
        notifiedKeys = emptyList(), images = emptyList(),
    ).toJson().toString()

    /**
     * 新旧比较：以 **updatedAt（修改时间）** 为准；
     * 时间相同才依次用 rev、createdAt、内容指纹做确定性破平，保证两端结论一致。
     * 返回正数表示 a 较新。
     */
    private fun compareNewer(a: ExpiryItem, b: ExpiryItem): Int {
        val ta = timestamp(a.updatedAt)
        val tb = timestamp(b.updatedAt)
        if (ta != tb) return ta.compareTo(tb)
        if (a.rev != b.rev) return a.rev.compareTo(b.rev)
        val ca = timestamp(a.createdAt)
        val cb = timestamp(b.createdAt)
        if (ca != cb) return ca.compareTo(cb)
        return contentOf(a).compareTo(contentOf(b))
    }

    /**
     * 计算合并计划（纯函数，可单测）。
     *
     * 规则：
     * 1. 先按 **共同唯一 ID（syncId）** 认同一条记录，再按本地 id、稳定业务键兜底；
     * 2. 认定为同一条后，比较 **updatedAt**，新者覆盖旧者；时间相同用 rev 破平；
     *    同时把双方的 syncId 收敛到同一个值，后续不再重复匹配；
     * 3. 业务键对不上（到期日/数量在一端改过）但同名单一候选时：
     *    双方都是循环物品且周期/数量一致 → 视为周期推进，自动合并；
     *    其余挂到待合并冲突由人工确认，不再悄悄插成第二条；
     * 4. 对端发来的记录若不高于本机删除墓碑的 rev，视为「已删物品被重新加回来」，直接丢弃；
     * 5. 找不到对应记录才作为新物品入库，保留对方 id / syncId / 图片引用。
     */
    fun plan(
        local: List<ExpiryItem>,
        remote: List<ExpiryItem>,
        from: String,
        /** 兼容旧调用点保留；新规则不再依赖「已同步 id」来避免误判冲突。 */
        syncedIds: Set<String> = emptySet(),
        /** 本机删除墓碑：挡住「这台设备刚删掉的东西被对端原样加回来」。 */
        tombstones: Map<String, Tombstone> = emptyMap(),
    ): SyncPlan {
        val bySyncId = HashMap<String, ExpiryItem>()
        val byId = HashMap<String, ExpiryItem>()
        val byKey = HashMap<String, MutableList<ExpiryItem>>()
        val byName = HashMap<String, MutableList<ExpiryItem>>()
        for (item in local) {
            if (item.syncId.isNotBlank()) bySyncId.putIfAbsent(item.syncId, item)
            byId.putIfAbsent(item.id, item)
            val key = syncKey(item)
            if (key.count { it == '|' } == 2 && key.substringBefore('|').isNotEmpty()) {
                byKey.getOrPut(key) { ArrayList() }.add(item)
            }
            val nameKey = Similarity.normalizeName(item.name)
            if (nameKey.isNotEmpty()) byName.getOrPut(nameKey) { ArrayList() }.add(item)
        }
        // 墓碑索引：id 与 syncId 双通道，物品可能只有其中一边对得上
        val tombById = HashMap<String, Long>()
        val tombBySyncId = HashMap<String, Long>()
        for (t in tombstones.values) {
            if (t.rev > 0L) {
                tombById[t.id] = maxOf(tombById[t.id] ?: 0L, t.rev)
                if (t.syncId.isNotBlank()) tombBySyncId[t.syncId] = maxOf(tombBySyncId[t.syncId] ?: 0L, t.rev)
            }
        }

        val adds = ArrayList<ExpiryItem>()
        val updates = ArrayList<ExpiryItem>()
        val conflicts = ArrayList<PendingConflict>()
        val touch = LinkedHashSet<String>()
        var skipped = 0

        fun isResurrected(item: ExpiryItem): Boolean {
            val revById = item.id.takeIf { it.isNotBlank() }?.let { tombById[it] } ?: 0L
            val revBySync = item.syncId.takeIf { it.isNotBlank() }?.let { tombBySyncId[it] } ?: 0L
            val tombRev = maxOf(revById, revBySync)
            // 对端记录的 rev 不高于墓碑：它还停留在删除前的版本，不能复活
            return tombRev > 0L && item.rev <= tombRev
        }

        for (incoming in remote) {
            if (isResurrected(incoming)) {
                skipped += 1
                continue
            }
            var match: ExpiryItem? = incoming.syncId.takeIf { it.isNotBlank() }
                ?.let { bySyncId[it] }
            if (match == null) match = byId[incoming.id]
            if (match == null) {
                val candidates = byKey[syncKey(incoming)]
                if (!candidates.isNullOrEmpty()) {
                    match = candidates.maxWithOrNull(
                        compareBy<ExpiryItem> { timestamp(it.updatedAt) }.thenBy { it.rev },
                    )
                }
            }

            // 兜底：业务键对不上（到期日/数量在一端被改过）但同名单一候选时，
            // 优先按「循环物品周期推进」识别为同一件；其余交人工确认，绝不悄悄插成第二条
            if (match == null) {
                val nameKey = Similarity.normalizeName(incoming.name)
                val candidates = byName[nameKey].orEmpty()
                if (candidates.size == 1) {
                    val local = candidates.first()
                    val sameCycle = incoming.repeatEnabled && local.repeatEnabled &&
                        incoming.shelfLife == local.shelfLife &&
                        incoming.shelfLifeUnit == local.shelfLifeUnit &&
                        incoming.quantity == local.quantity
                    if (sameCycle) {
                        match = local
                    } else if (incoming.expireAt != local.expireAt || incoming.quantity != local.quantity) {
                        conflicts += PendingConflict(
                            key = local.id + "|" + incoming.id,
                            local = local,
                            incoming = incoming,
                            score = 1.0,
                            reason = "同名物品但到期日/数量不一致，请确认是同一件还是两件",
                            from = from,
                            detectedAt = Dates.nowIsoTimestamp(),
                        )
                        continue
                    }
                }
            }

            if (match != null) {
                val canonical = canonicalId(match, incoming)
                val cmp = compareNewer(incoming, match)
                val sameContent = contentOf(incoming) == contentOf(match)
                when {
                    cmp > 0 -> {
                        // 对方修改时间更新：直接覆盖本机，不弹冲突
                        updates += incoming.copy(
                            id = match.id,
                            syncId = canonical,
                            createdAt = match.createdAt,
                            images = match.images,
                            notifiedKeys = if (incoming.expireAt != match.expireAt) emptyList()
                            else match.notifiedKeys,
                        )
                    }
                    cmp < 0 || !sameContent -> {
                        // 本机较新：抬 rev 让下一轮把本机版本回传给对方
                        touch += match.id
                        if (match.syncId != canonical) updates += match.copy(syncId = canonical)
                    }
                    match.syncId != canonical -> updates += match.copy(syncId = canonical)
                    else -> skipped++
                }
                continue
            }

            // 新物品：保留对方 id / syncId，两台设备从此共用同一条记录
            adds += incoming.copy(
                syncId = stableId(incoming),
                notifiedKeys = emptyList(),
            )
        }
        return SyncPlan(adds, updates, conflicts, skipped, touch.toList())
    }

    /**
     * 手动「智能合并」：以本机记录为基底，空字段由对方补齐，冲突字段取更保守的值。
     * 到期日取较早（宁可早提醒），备注拼接，提醒档位取并集，图片始终保留本机。
     * 新规则下几乎不会触发，仅为历史冲突记录保留处理能力。
     */
    fun mergeFields(local: ExpiryItem, incoming: ExpiryItem): ExpiryItem {
        fun pick(a: String, b: String): String = a.ifBlank { b }
        val earlier = when {
            local.expireAt.isBlank() -> incoming.expireAt
            incoming.expireAt.isBlank() -> local.expireAt
            else -> minOf(local.expireAt, incoming.expireAt)
        }
        val notes = listOf(local.note.trim(), incoming.note.trim())
            .filter { it.isNotEmpty() }.distinct().joinToString(" / ")
        return local.copy(
            name = pick(local.name, incoming.name),
            category = if (local.category == Categories.DEFAULT && incoming.category != Categories.DEFAULT)
                incoming.category else local.category,
            spaceId = pick(local.spaceId, incoming.spaceId),
            productionDate = pick(local.productionDate, incoming.productionDate),
            shelfLife = if (local.shelfLife > 0) local.shelfLife else incoming.shelfLife,
            shelfLifeUnit = if (local.shelfLife > 0) local.shelfLifeUnit else incoming.shelfLifeUnit,
            expireAt = earlier,
            quantity = local.quantity,
            note = notes,
            remindDays = normalizeRemindDays(local.remindDays + incoming.remindDays),
            images = local.images,
        )
    }
}

/** 待合并冲突的本地持久化：单独文件，不改 data.json 的版本。 */
object ConflictStore {

    private const val FILE = "auto_sync_conflicts.json"
    private val _conflicts = MutableStateFlow<List<PendingConflict>>(emptyList())
    val conflicts: StateFlow<List<PendingConflict>> = _conflicts.asStateFlow()

    private fun file(context: Context): File = File(ImageStore.baseDir(context), FILE)

    @Synchronized
    fun load(context: Context) {
        val f = file(context)
        if (!f.exists()) {
            _conflicts.value = emptyList()
            return
        }
        val arr = runCatching { JSONArray(f.readText()) }.getOrNull() ?: JSONArray()
        val list = ArrayList<PendingConflict>()
        for (i in 0 until arr.length()) {
            PendingConflict.fromJson(arr.opt(i))?.let { list += it }
        }
        _conflicts.value = list.distinctBy { it.key }
    }

    /** 合入新冲突，按 key 去重；返回真正新增的条数。 */
    @Synchronized
    fun add(context: Context, incoming: List<PendingConflict>): Int {
        if (incoming.isEmpty()) return 0
        val existing = _conflicts.value.map { it.key }.toMutableSet()
        val merged = _conflicts.value.toMutableList()
        var added = 0
        for (c in incoming) {
            if (existing.add(c.key)) {
                merged += c
                added++
            }
        }
        if (added == 0) return 0
        _conflicts.value = merged
        persist(context, merged)
        return added
    }

    @Synchronized
    fun remove(context: Context, key: String) {
        val next = _conflicts.value.filterNot { it.key == key }
        if (next.size == _conflicts.value.size) return
        _conflicts.value = next
        persist(context, next)
    }

    @Synchronized
    fun clear(context: Context) {
        _conflicts.value = emptyList()
        persist(context, emptyList())
    }

    private fun persist(context: Context, list: List<PendingConflict>) {
        runCatching {
            AtomicFiles.writeText(file(context), JSONArray(list.map { it.toJson() }).toString(2))
        }
    }
}
