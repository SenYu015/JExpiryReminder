package com.jimmy.expiryreminder.core

import android.content.Context

/**
 * 可信设备表：只有**手动配对过**的设备才能参与后台自动同步。
 *
 * 背景：自动同步是免交互的常驻通道（mDNS 广播 `_jexpiry-auto._tcp.`），
 * 如果只凭「同一 Wi-Fi」就交换数据，同网任何设备（同款 App 或照协议实现）
 * 都能把全量物品读走、甚至按 rev 覆盖注入。所以把身份收敛到一次显式配对：
 *
 * - 配对由手动「跨设备分享」完成（用户比对 4 位码 → 点接受）；
 * - 接收端生成 32 字节随机密钥，保存在本机，并随配对响应回传给发起方；
 * - 双方之后每次自动同步都用这把密钥做 HMAC 认证（见 [AutoSyncEngine]），
 *   没有密钥的请求一律拒绝，连一条物品都不会回。
 *
 * 密钥存 SharedPreferences，随应用卸载消失；应用内的备份导出不包含它，
 * 换机后需要重新配对——这是刻意的，避免备份文件泄露直接把同步通道也交出去。
 */
object PeerTrust {

    private const val PREFS = "peer_trust"
    private const val KEY_PREFIX = "secret_"
    private const val NAME_PREFIX = "name_"
    private const val TIME_PREFIX = "at_"

    data class Trusted(val id: String, val name: String, val secret: String)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun of(context: Context, deviceId: String): Trusted? {
        if (deviceId.isBlank()) return null
        val p = prefs(context)
        val secret = p.getString(KEY_PREFIX + deviceId, null)?.takeIf { it.isNotBlank() } ?: return null
        val name = p.getString(NAME_PREFIX + deviceId, null)?.takeIf { it.isNotBlank() } ?: deviceId
        return Trusted(deviceId, name, secret)
    }

    fun all(context: Context): List<Trusted> {
        val p = prefs(context)
        return p.all.keys
            .filter { it.startsWith(KEY_PREFIX) }
            .mapNotNull { key -> of(context, key.removePrefix(KEY_PREFIX)) }
            .sortedBy { p.getLong(TIME_PREFIX + it.id, 0L) }
            .reversed()
    }

    fun count(context: Context): Int = all(context).size

    /**
     * 幂等登记：同一台设备重复配对时复用已有密钥，
     * 否则对方手上的旧密钥立刻失效，自动同步会静默停掉。
     */
    fun ensure(context: Context, deviceId: String, name: String): String {
        of(context, deviceId)?.let { existing ->
            prefs(context).edit().putString(NAME_PREFIX + deviceId, name).apply()
            return existing.secret
        }
        val secret = Crypto.newSecret()
        save(context, deviceId, name, secret)
        return secret
    }

    fun save(context: Context, deviceId: String, name: String, secret: String) {
        if (deviceId.isBlank() || secret.isBlank()) return
        prefs(context).edit()
            .putString(KEY_PREFIX + deviceId, secret)
            .putString(NAME_PREFIX + deviceId, name.ifBlank { deviceId })
            .putLong(TIME_PREFIX + deviceId, System.currentTimeMillis())
            .apply()
    }

    fun revoke(context: Context, deviceId: String) {
        prefs(context).edit()
            .remove(KEY_PREFIX + deviceId)
            .remove(NAME_PREFIX + deviceId)
            .remove(TIME_PREFIX + deviceId)
            .apply()
    }
}
