package com.jimmy.expiryreminder.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 增量同步的纯函数：按修改标记筛选。 */
object SyncDelta {
    fun pick(items: List<ExpiryItem>, sinceRev: Long): List<ExpiryItem> =
        items.filter { it.rev > sinceRev }

    fun maxRev(items: List<ExpiryItem>): Long = items.maxOfOrNull { it.rev } ?: 0L
}

/** 对每台设备记两个水位，全部存在本机，不上传任何服务器。 */
data class PeerSyncState(
    /** 我已从对方收到的最大 rev：请求里作为 sinceRev，让对方别再发旧数据。 */
    val recvRev: Long = 0L,
    /** 对方已确认收到的、我发出的最大 rev：用于筛出本次要发的增量。 */
    val sentRev: Long = 0L,
    val lastSyncAt: String = "",
)

/**
 * 自动同步水位与「已同步过的物品 id」。
 *
 * 单独文件、不进 data.json，也不参与备份：换机/恢复备份后水位从 0 开始，
 * 最多多传一轮全量，不会丢数据。所有方法同步加锁，TCP 两端并发写也安全。
 */
object SyncStateStore {

    private const val FILE = "auto_sync_state.json"
    private const val MAX_SYNCED_IDS = 20_000

    private var loaded = false
    private val peers = LinkedHashMap<String, PeerSyncState>()
    private val syncedIds = LinkedHashSet<String>()

    private fun file(context: Context): File = File(ImageStore.baseDir(context), FILE)

    @Synchronized
    private fun ensure(context: Context) {
        if (loaded) return
        loaded = true
        val f = file(context)
        val root = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrNull() ?: return
        else return
        root.optJSONObject("peers")?.let { obj ->
            for (key in obj.keys()) {
                val o = obj.optJSONObject(key) ?: continue
                peers[key] = PeerSyncState(
                    recvRev = o.optLong("recvRev", 0L),
                    sentRev = o.optLong("sentRev", 0L),
                    lastSyncAt = o.optStringOrNull("lastSyncAt") ?: "",
                )
            }
        }
        root.optJSONArray("syncedIds")?.let { arr ->
            for (i in 0 until arr.length()) {
                (arr.opt(i) as? String)?.takeIf { it.isNotBlank() }?.let { syncedIds += it }
            }
        }
    }

    @Synchronized
    fun peer(context: Context, id: String): PeerSyncState {
        ensure(context)
        return peers[id] ?: PeerSyncState()
    }

    /** 只做 max 推进，水位永不回退；负数表示该方向本次没有新数据。 */
    @Synchronized
    fun update(
        context: Context,
        id: String,
        recvMax: Long = -1L,
        sentMax: Long = -1L,
    ): PeerSyncState {
        ensure(context)
        val cur = peers[id] ?: PeerSyncState()
        val next = PeerSyncState(
            recvRev = maxOf(cur.recvRev, recvMax, 0L),
            sentRev = maxOf(cur.sentRev, sentMax, 0L),
            lastSyncAt = Dates.nowIsoTimestamp(),
        )
        peers[id] = next
        persist(context)
        return next
    }

    /** 已经同步过的设备数（设置页展示「已配对 N 台」）。 */
    @Synchronized
    fun peerCount(context: Context): Int {
        ensure(context)
        return peers.size
    }

    @Synchronized
    fun syncedIds(context: Context): Set<String> {
        ensure(context)
        return syncedIds.toSet()
    }

    /** 记录「这条来自别的设备」，避免历史同步数据被重复判成冲突。 */
    @Synchronized
    fun markSynced(context: Context, ids: Collection<String>) {
        ensure(context)
        val before = syncedIds.size
        for (id in ids) {
            if (syncedIds.size >= MAX_SYNCED_IDS) break
            if (id.isNotBlank()) syncedIds += id
        }
        if (syncedIds.size != before) persist(context)
    }

    private fun persist(context: Context) {
        val obj = JSONObject()
        val peerJson = JSONObject()
        for ((id, state) in peers) {
            peerJson.put(
                id,
                JSONObject()
                    .put("recvRev", state.recvRev)
                    .put("sentRev", state.sentRev)
                    .put("lastSyncAt", state.lastSyncAt),
            )
        }
        obj.put("peers", peerJson)
        obj.put("syncedIds", JSONArray(syncedIds.toList()))
        runCatching { file(context).writeText(obj.toString(2)) }
    }
}
