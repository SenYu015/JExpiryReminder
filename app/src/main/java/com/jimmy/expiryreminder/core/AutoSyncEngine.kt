package com.jimmy.expiryreminder.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import com.jimmy.expiryreminder.notify.AutoSyncReceiver
import com.jimmy.expiryreminder.notify.ReminderService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 局域网自动同步引擎（与手动「跨设备传输」完全独立，互不影响）。
 *
 * - 应用进程存活期间常驻：mDNS 广播自己的 TCP 端口 + 发现同伴，每 20 秒合并一轮；
 * - 进程被系统回收时，由 AlarmManager 每 30 分钟拉起一次做兜底同步；
 * - 交换的是**增量**文本记录（按 rev 修改标记筛过，不传图片文件）；两边都没改动时
 *   只做一次空握手，不写库、不通知；
 * - 同一条记录以 rev 较大者直接合并；只有两台设备各自独立录入的相似物品才挂到
 *   待合并冲突，由用户手动决定，绝不悄悄覆盖用户数据。
 */
object AutoSyncEngine {

    const val ACTION_SYNC = "com.jimmy.expiryreminder.action.AUTO_SYNC"
    private const val APP = "JExpiryReminder"
    private const val SERVICE_TYPE = "_jexpiry-auto._tcp."
    private const val TICK_MS = 20_000L
    private const val PEER_COOLDOWN_MS = 25_000L
    private const val ALARM_INTERVAL_MS = 30 * 60 * 1000L
    private const val MAX_LINE = 4 * 1024 * 1024
    private const val NOTIFICATION_ID = 9001
    /** 认证时间窗：超过这个偏差的请求直接拒绝，配合 nonce 去重挡重放。 */
    private const val AUTH_WINDOW_MS = 120_000L

    /** 单轮最多拉多少张图、单张与单轮的总字节上限，避免一次同步把手机拖住。 */
    private const val MAX_IMAGE_FILES = 80
    private const val MAX_IMAGE_BYTES = 6L * 1024 * 1024
    private const val MAX_IMAGE_TOTAL = 40L * 1024 * 1024
    private const val IMAGE_INDEX_LIMIT = 800

    data class Peer(val serviceName: String, val id: String, val name: String, val host: String, val port: Int)

    private val _status = MutableStateFlow("未开启")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private var watchScope: CoroutineScope? = null
    private var engineScope: CoroutineScope? = null
    private var appContext: Context? = null
    private var nsd: NsdManager? = null
    private var server: ServerSocket? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var started = false
    private var watching = false
    private val seen = ConcurrentHashMap.newKeySet<String>()
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private val resolving = AtomicBoolean(false)
    private val lastSyncAt = ConcurrentHashMap<String, Long>()
    /** 最近见过的 (设备, nonce)，用于挡住重放；只保留时间窗内的条目。 */
    private val recentNonces = ConcurrentHashMap<String, Long>()

    /** 由 Application 调用一次：跟随设置开关自动启停，并载入待合并冲突。 */
    @Synchronized
    fun watch(context: Context) {
        val app = context.applicationContext
        appContext = app
        ConflictStore.load(app)
        if (watching) return
        watching = true
        watchScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
            scope.launch {
                SettingsStore.get().autoSyncEnabled.collect { enabled ->
                    if (enabled) start(app) else stop()
                }
            }
        }
    }

    @Synchronized
    private fun start(context: Context) {
        if (started) return
        val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (manager == null) {
            _status.value = "本机不支持局域网发现（NsdManager 不可用）"
            return
        }
        val socket = runCatching { ServerSocket(0) }.getOrElse {
            _status.value = "无法监听端口：${it.message}"
            return
        }
        started = true
        nsd = manager
        server = socket
        appContext = context
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        engineScope = scope
        _status.value = "已开启 · 正在搜索同一 Wi-Fi 下的设备"

        val id = deviceId(context)
        val info = NsdServiceInfo().apply {
            serviceName = "JEXA-${id.take(8)}"
            serviceType = SERVICE_TYPE
            setPort(socket.localPort)
            setAttribute("id", id)
            setAttribute("name", deviceName())
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                _status.value = "本机广播失败（$errorCode），对方可能搜不到这台设备"
            }
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = registration
        runCatching { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _status.value = "已开启 · 正在搜索同一 Wi-Fi 下的设备"
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != SERVICE_TYPE) return
                if (seen.add(serviceInfo.serviceName)) enqueueResolve(serviceInfo)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                seen.remove(serviceInfo.serviceName)
                _peers.value = _peers.value.filterNot { it.serviceName == serviceInfo.serviceName }
            }
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                _status.value = "局域网发现启动失败（$errorCode），可检查 Wi-Fi 后重试"
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = discovery
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }

        scope.launch { acceptLoop(socket, context) }
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick(context)
            }
        }
        scheduleAlarm(context)
    }

    @Synchronized
    private fun stop() {
        started = false
        engineScope?.cancel()
        engineScope = null
        discoveryListener?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        registrationListener?.let { runCatching { nsd?.unregisterService(it) } }
        runCatching { server?.close() }
        server = null
        discoveryListener = null
        registrationListener = null
        nsd = null
        seen.clear()
        synchronized(resolveQueue) { resolveQueue.clear() }
        resolving.set(false)
        lastSyncAt.clear()
        recentNonces.clear()
        _peers.value = emptyList()
        _status.value = "未开启"
        appContext?.let { cancelAlarm(it) }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        drainResolve()
    }

    /** resolveService 并发调用会 FAILURE_ALREADY_ACTIVE，必须串成队列。 */
    private fun drainResolve() {
        val manager = nsd ?: return
        if (!resolving.compareAndSet(false, true)) return
        val next = synchronized(resolveQueue) { resolveQueue.pollFirst() }
        if (next == null) {
            resolving.set(false)
            return
        }
        fun finish() {
            resolving.set(false)
            drainResolve()
        }
        @Suppress("DEPRECATION")
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                seen.remove(info.serviceName)
                finish()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                addPeer(info)
                finish()
            }
        }
        val ok = runCatching {
            @Suppress("DEPRECATION")
            manager.resolveService(next, listener)
        }.isSuccess
        if (!ok) {
            seen.remove(next.serviceName)
            finish()
        }
    }

    @Suppress("DEPRECATION")
    private fun addPeer(info: NsdServiceInfo) {
        val addresses = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses
        else listOfNotNull(info.host)
        val host = (addresses.filterIsInstance<Inet4Address>().firstOrNull()
            ?: addresses.firstOrNull())?.hostAddress ?: return
        val port = info.port
        if (port <= 0) return
        val attrs = info.attributes.orEmpty()
        val id = attrs["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: info.serviceName
        if (id == deviceId(appContext ?: return)) return
        val name = attrs["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
            ?: info.serviceName
        val peer = Peer(info.serviceName, id, name, host, port)
        _peers.value = _peers.value.filterNot { it.id == id } + peer
    }

    private fun CoroutineScope.acceptLoop(socket: ServerSocket, context: Context) {
        val slots = Semaphore(4)
        while (isActive) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            launch { slots.withPermit { handleClient(client, context) } }
        }
    }

    /** 对方连进来：读它的快照 → 合并 → 回一份合并后的本机快照。 */
    private suspend fun handleClient(client: Socket, context: Context) {
        client.use { s ->
            s.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            // 边读边计数：BufferedReader.readLine() 会先把整行读进内存，超长行直接 OOM
            val line = readLimitedLine(reader, MAX_LINE) ?: return
            val req = runCatching { JSONObject(line) }.getOrNull() ?: return
            if (req.optString("app") != APP) return
            val cmd = req.optString("cmd")
            if (cmd != "sync" && cmd != "imgs") return
            val peerId = req.optString("from")
            val nonce = req.optString("nonce")
            // 只认手动配对过的设备；没有密钥就不回任何数据（连错误细节都尽量少给）
            val trust = PeerTrust.of(context, peerId)
                ?: return reject(s, null, nonce, "未配对设备")
            if (!authenticated(trust.secret, cmd, peerId, req)) {
                return reject(s, trust.secret, nonce, "认证失败")
            }
            if (cmd == "imgs") {
                sendImages(context, req, s, trust.secret)
                return
            }
            // 物品、分类/空间、图片索引都在加密载荷里：同网嗅探只能看到一条密文
            val inbound = decBlob(trust.secret, req.optString("payload"))
                ?: return reject(s, trust.secret, nonce, "载荷无法解密")
            val sinceRev = inbound.optLong("sinceRev", 0L)
            val remote = parseItems(inbound.optJSONArray("items"))
            val remoteTombstones = parseTombstones(inbound.optJSONArray("tombstones"))
            if (remote.size > TransferPayload.MAX_ITEMS) {
                return reject(s, trust.secret, nonce, "物品数量异常")
            }
            val remoteMeta = MetaSnapshot.fromJson(inbound.optJSONObject("meta"))
            val remoteImages = parseImageIndex(inbound.optJSONObject("imageIndex"))
            val fromName = inbound.optString("name").ifBlank { peerId }
            // 分类/空间可能单独变化（物品没动），所以无条件走一次归并
            applyRemote(
                context, remote, fromName.ifBlank { "另一台设备" },
                remoteMeta, remoteImages, remoteTombstones,
            )
            // 对方声称已收到我发出的 <= sinceRev 的记录；同时推进「我从对方收到的」水位
            if (peerId.isNotBlank()) {
                SyncStateStore.update(
                    context, peerId,
                    recvMax = maxRevOf(remote, remoteTombstones),
                    sentMax = sinceRev,
                )
            }
            // 只回对方没见过的增量；两边都没改动时就是一次空握手，不写库
            val outgoing = ExpiryStore.get().itemsSince(sinceRev)
            val body = JSONObject()
                .put("name", deviceName())
                .put("items", itemsJson(outgoing.items))
                .put("tombstones", tombstonesJson(outgoing.tombstones))
                .put("meta", localMeta().toJson())
                .put("imageIndex", imageIndexJson())
                .put("ackRev", maxRevOf(remote, remoteTombstones))
                .put("revNow", ExpiryStore.get().revision)
            val resp = JSONObject()
                .put("ok", true)
                .put("from", deviceId(context))
                .put("enc", "aes-gcm")
                .put("payload", encBlob(trust.secret, body) ?: "")
                .put("auth", Crypto.hmacHex(trust.secret, "resp|$nonce|true") ?: "")
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writer.write(resp.toString() + "\n")
            writer.flush()
        }
    }

    /** 载荷加密成 Base64；失败返回 null，由调用方按「拒绝」处理。 */
    private fun encBlob(secret: String, json: JSONObject): String? {
        val cipherText = Crypto.encrypt(secret, json.toString().toByteArray(Charsets.UTF_8)) ?: return null
        return android.util.Base64.encodeToString(cipherText, android.util.Base64.NO_WRAP)
    }

    private fun decBlob(secret: String, text: String): JSONObject? {
        if (text.isBlank()) return null
        val blob = runCatching {
            android.util.Base64.decode(text, android.util.Base64.NO_WRAP)
        }.getOrNull() ?: return null
        val plain = Crypto.decrypt(secret, blob) ?: return null
        return runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
    }

    /** 限长读取一行，超长直接返回 null；不把整条超长行先读进内存。 */
    private fun readLimitedLine(reader: java.io.Reader, max: Int): String? {
        val sb = StringBuilder(1024)
        while (true) {
            val c = reader.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c == '\r'.code) continue
            if (sb.length >= max) return null
            sb.append(c.toChar())
        }
    }

    /**
     * 校验请求签名：`HMAC(secret, "cmd|from|nonce|ts")`。
     * 时间窗挡旧包、nonce 去重挡重放，二者缺一都能让抓到的一次请求被反复提交。
     */
    private fun authenticated(secret: String, cmd: String, fromId: String, req: JSONObject): Boolean {
        val nonce = req.optString("nonce")
        val ts = req.optLong("ts", 0L)
        if (nonce.length !in 16..64) return false
        if (kotlin.math.abs(System.currentTimeMillis() - ts) > AUTH_WINDOW_MS) return false
        val expect = Crypto.hmacHex(secret, "$cmd|$fromId|$nonce|$ts") ?: return false
        if (!Crypto.constantTimeEquals(expect, req.optString("auth"))) return false
        return acceptNonce(fromId, nonce)
    }

    private fun acceptNonce(fromId: String, nonce: String): Boolean {
        val now = System.currentTimeMillis()
        recentNonces.entries.removeAll { now - it.value > AUTH_WINDOW_MS }
        return recentNonces.putIfAbsent("$fromId|$nonce", now) == null
    }

    /**
     * 回一条拒绝。已知密钥时连拒绝也签名，客户端才能区分
     * 「真设备的拒绝」与「冒名顶替者的拒绝」，避免把中间人当成对端。
     */
    private fun reject(socket: Socket, secret: String?, nonce: String, message: String) {
        val body = JSONObject().put("ok", false).put("error", message)
        if (!secret.isNullOrBlank() && nonce.isNotBlank()) {
            Crypto.hmacHex(secret, "resp|$nonce|false")?.let { body.put("auth", it) }
        }
        runCatching {
            val out = OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8)
            out.write(body.toString() + "\n")
            out.flush()
        }
    }

    private suspend fun tick(context: Context) {
        val list = _peers.value
        if (list.isEmpty()) return
        var ok = 0
        var unpaired = 0
        var lastError: String? = null
        for (peer in list) {
            // 未配对的设备既不读也不写：这是后台通道唯一的口子，必须显式授权过
            if (PeerTrust.of(context, peer.id) == null) {
                unpaired += 1
                continue
            }
            val last = lastSyncAt[peer.id] ?: 0L
            if (System.currentTimeMillis() - last < PEER_COOLDOWN_MS) continue
            lastSyncAt[peer.id] = System.currentTimeMillis()
            runCatching {
                val remote = exchange(peer.host, peer.port, context, peer.id)
                applyRemote(context, remote.items, peer.name, remote.meta, remote.imageIndex, remote.tombstones)
                fetchMissingImages(context, peer.host, peer.port, peer.id)
                pruneAckedTombstones(context)
            }.onSuccess { ok += 1 }.onFailure { lastError = it.message }
        }
        _status.value = when {
            ok > 0 -> "已开启 · ${timeNow()} 已同步 $ok 台设备"
            unpaired > 0 && unpaired == list.size ->
                "已开启 · $unpaired 台设备未配对，先在「跨设备分享」里配对"
            lastError != null -> "同步失败：$lastError"
            else -> _status.value
        }
    }

    /** 供「立即同步」按钮与定时广播调用；返回给用户看的结果文案。 */
    suspend fun syncNow(context: Context): String {
        val app = context.applicationContext
        if (!SettingsStore.get().autoSyncEnabled.value) return "请先在设置里开启自动同步"
        if (!started) start(app)
        // 进程刚被闹钟拉起时 mDNS 还没解析完，稍等一下
        if (_peers.value.isEmpty()) delay(3_000)
        val list = _peers.value
        if (list.isEmpty()) return "暂未发现同一 Wi-Fi 下的设备（两台设备都要开启自动同步）"
        var ok = 0
        var unpaired = 0
        var lastError: String? = null
        for (peer in list) {
            if (PeerTrust.of(app, peer.id) == null) {
                unpaired += 1
                continue
            }
            runCatching {
                val remote = exchange(peer.host, peer.port, app, peer.id)
                applyRemote(app, remote.items, peer.name, remote.meta, remote.imageIndex, remote.tombstones)
                fetchMissingImages(app, peer.host, peer.port, peer.id)
                pruneAckedTombstones(app)
            }.onSuccess {
                ok++
                lastSyncAt[peer.id] = System.currentTimeMillis()
            }.onFailure { lastError = it.message }
        }
        val text = when {
            ok > 0 -> "已与 $ok 台设备同步"
            unpaired > 0 -> "发现 $unpaired 台未配对设备，请先在「跨设备分享」里配对"
            else -> "同步失败：${lastError ?: "连接不上"}"
        }
        _status.value = "已开启 · ${timeNow()} $text"
        return text
    }

    private suspend fun exchange(host: String, port: Int, context: Context, peerId: String): RemotePayload {
        val trust = PeerTrust.of(context, peerId)
            ?: error("未配对设备，请先在「跨设备分享」里配对")
        val selfId = deviceId(context)
        val nonce = Crypto.randomHex(16)
        val stamp = System.currentTimeMillis()
        val auth = Crypto.hmacHex(trust.secret, "sync|$selfId|$nonce|$stamp")
            ?: error("本机信任密钥损坏，请重新配对")
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 6_000)
            s.soTimeout = 12_000
            val state = SyncStateStore.peer(context, peerId)
            // 只发「对方还没确认收到」的变更；无变更时 items 为空
            val outgoing = ExpiryStore.get().itemsSince(state.sentRev)
            val body = JSONObject()
                .put("name", deviceName())
                .put("sinceRev", state.recvRev)
                .put("sentRev", state.sentRev)
                .put("meta", localMeta().toJson())
                .put("imageIndex", imageIndexJson())
                .put("items", itemsJson(outgoing.items))
                .put("tombstones", tombstonesJson(outgoing.tombstones))
            val req = JSONObject()
                .put("cmd", "sync")
                .put("app", APP)
                .put("schema", 5)
                .put("from", selfId)
                .put("nonce", nonce)
                .put("ts", stamp)
                .put("auth", auth)
                .put("enc", "aes-gcm")
                .put("payload", encBlob(trust.secret, body) ?: error("载荷加密失败"))
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writer.write(req.toString() + "\n")
            writer.flush()
            val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                .let { readLimitedLine(it, MAX_LINE) } ?: error("对方没有返回数据")
            val resp = JSONObject(line)
            val respOk = resp.optBoolean("ok")
            val expectAuth = Crypto.hmacHex(trust.secret, "resp|$nonce|$respOk")
            if (!Crypto.constantTimeEquals(expectAuth, resp.optString("auth"))) {
                error("对方身份校验失败，请重新配对")
            }
            if (!respOk) error(resp.optString("error").ifBlank { "对方拒绝了同步" })
            val inbound = decBlob(trust.secret, resp.optString("payload"))
                ?: error("返回数据无法解密，请重新配对")
            val remote = parseItems(inbound.optJSONArray("items"))
            val remoteTombstones = parseTombstones(inbound.optJSONArray("tombstones"))
            if (remote.size > TransferPayload.MAX_ITEMS) error("对端物品数量异常")
            SyncStateStore.update(
                context, peerId,
                recvMax = maxRevOf(remote, remoteTombstones),
                sentMax = inbound.optLong("ackRev", 0L),
            )
            // 把本机时钟抬到对方水位，保证之后本机编辑的 rev 一定更大
            ExpiryStore.get().observeRemoteClock(context, inbound.optLong("revNow", 0L))
            return RemotePayload(
                items = remote,
                meta = MetaSnapshot.fromJson(inbound.optJSONObject("meta")),
                imageIndex = parseImageIndex(inbound.optJSONObject("imageIndex")),
                tombstones = remoteTombstones,
            )
        }
    }

    private data class RemotePayload(
        val items: List<ExpiryItem>,
        val meta: MetaSnapshot,
        val imageIndex: Map<String, List<String>>,
        val tombstones: List<Tombstone> = emptyList(),
    )

    /** 物品 id → 图片相对路径列表：只带路径，不带字节，用来发现「本地缺哪几张图」。 */
    private fun imageIndexJson(): JSONObject {
        val root = JSONObject()
        var count = 0
        for (item in ExpiryStore.get().items) {
            if (item.images.isEmpty()) continue
            if (count++ >= IMAGE_INDEX_LIMIT) break
            root.put(item.id, JSONArray(item.images.map { it.path }))
        }
        return root
    }

    private fun parseImageIndex(raw: JSONObject?): Map<String, List<String>> {
        if (raw == null) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for (key in raw.keys()) {
            val arr = raw.optJSONArray(key) ?: continue
            val paths = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                (arr.opt(i) as? String)?.let { paths += it }
            }
            if (paths.isNotEmpty()) out[key] = paths
        }
        return out
    }

    /** 本机分类/空间快照：每轮同步都带全量（体积很小），归并规则在 [MetaSync] 里。 */
    private fun localMeta(): MetaSnapshot {
        val s = ExpiryStore.get().state.value
        return MetaSnapshot(
            categories = s.categories,
            categoryColors = s.categoryColors,
            categoryLabels = s.categoryLabels,
            categoryOrder = s.order,
            spaces = s.spaces,
            spaceColors = s.spaceColors,
        )
    }

    private suspend fun applyRemote(
        context: Context,
        remote: List<ExpiryItem>,
        from: String,
        remoteMeta: MetaSnapshot? = null,
        remoteImageIndex: Map<String, List<String>> = emptyMap(),
        remoteTombstones: List<Tombstone> = emptyList(),
    ): SyncPlan {
        val store = ExpiryStore.get()
        // 先归并分类/空间，再把远端物品上的引用改到本机 id，最后才做物品合并
        val incoming = if (remoteMeta != null) {
            val metaPlan = MetaSync.plan(localMeta(), remoteMeta)
            store.applyMetaSync(context, metaPlan)
            MetaSync.remap(remote, metaPlan)
        } else remote
        store.observeRemoteClock(context, maxRevOf(incoming, remoteTombstones))
        // 删除先落地：合并计划要基于删除后的列表，墓碑同时进本机墓碑表
        val appliedDeletes = store.applyRemoteDeletions(context, remoteTombstones)
        val plan = AutoSyncMerge.plan(
            store.items, incoming, from, SyncStateStore.syncedIds(context),
            store.tombstoneMap(),
        )
        store.applyAutoSyncItems(context, plan.adds, plan.updates)
        // 本机较新的记录抬 rev，下一轮增量会把它回传给对方完成收敛
        if (plan.touchIds.isNotEmpty()) store.bumpRevForSync(context, plan.touchIds)
        if (plan.adds.isNotEmpty() || plan.updates.isNotEmpty() || plan.touchIds.isNotEmpty()) {
            SyncStateStore.markSynced(
                context,
                plan.adds.map { it.id } + plan.updates.map { it.id } + plan.touchIds,
            )
        }
        if (appliedDeletes > 0) {
            ReminderService.showNotification(
                context, NOTIFICATION_ID,
                "同步删除 $appliedDeletes 件物品",
                "另一台设备删掉的物品已同步删除",
            )
        }
        val newConflicts = ConflictStore.add(context, plan.conflicts)
        if (newConflicts > 0) {
            ReminderService.showNotification(
                context, NOTIFICATION_ID + 1,
                "有 $newConflicts 件物品需要确认",
                "两台设备各自录入了疑似重复物品，点开后到「设置 → 待合并冲突」手动合并",
            )
        }
        // 给本地缺图的物品挂上对端引用，文件由本轮/下一轮 fetchMissingImages 拉取
        if (remoteImageIndex.isNotEmpty()) repairImageAttachments(context, remoteImageIndex)
        return plan
    }

    /** 记录与墓碑的最高 rev，用作水位推进。 */
    private fun maxRevOf(items: List<ExpiryItem>, tombstones: List<Tombstone>): Long =
        maxOf(
            items.maxOfOrNull { it.rev } ?: 0L,
            tombstones.maxOfOrNull { it.rev } ?: 0L,
        )

    private fun tombstonesJson(tombstones: List<Tombstone>): JSONArray =
        JSONArray(tombstones.map { it.toJson() })

    private fun parseTombstones(arr: JSONArray?): List<Tombstone> {
        if (arr == null) return emptyList()
        val out = ArrayList<Tombstone>()
        for (i in 0 until arr.length()) {
            Tombstone.fromJson(arr.opt(i))?.let { out += it }
        }
        return out
    }

    /** 所有已配对设备都确认收过的墓碑可以裁掉，防止墓碑表无限增长。 */
    private suspend fun pruneAckedTombstones(context: Context) {
        val acked = SyncStateStore.minAckedRev(context)
        if (acked > 0L) ExpiryStore.get().pruneTombstones(context, acked)
    }

    /** 只给「本地没有图片」的物品挂引用，不覆盖本机已有图片。 */
    private suspend fun repairImageAttachments(
        context: Context,
        index: Map<String, List<String>>,
    ) {
        val store = ExpiryStore.get()
        for ((itemId, paths) in index) {
            val item = store.getItem(itemId) ?: continue
            if (item.images.isNotEmpty()) continue
            val images = paths.mapNotNull { raw ->
                val safe = normalizeImagePath(raw)
                if (safe.isEmpty()) null
                else ExpiryImage(
                    id = safe.substringAfterLast('/').substringBeforeLast('.'),
                    path = safe,
                    addedAt = Dates.nowIsoTimestamp(),
                )
            }.take(MAX_IMAGES)
            if (images.isNotEmpty()) store.attachImages(context, itemId, images)
        }
    }

    /** 把本机缺的图片文件从对端拉回来；单轮有条数/字节上限。 */
    private fun fetchMissingImages(context: Context, host: String, port: Int, peerId: String) {
        val trust = PeerTrust.of(context, peerId) ?: return
        val missing = LinkedHashSet<String>()
        for (item in ExpiryStore.get().items) {
            for (image in item.images) {
                val safe = normalizeImagePath(image.path)
                if (safe.isEmpty()) continue
                if (!ImageStore.imageFile(context, safe).exists()) missing += safe
                if (missing.size >= MAX_IMAGE_FILES) break
            }
            if (missing.size >= MAX_IMAGE_FILES) break
        }
        if (missing.isEmpty()) return

        val selfId = deviceId(context)
        val nonce = Crypto.randomHex(16)
        val stamp = System.currentTimeMillis()
        val auth = Crypto.hmacHex(trust.secret, "imgs|$selfId|$nonce|$stamp") ?: return

        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 6_000)
                s.soTimeout = 20_000
                val out = BufferedOutputStream(s.getOutputStream())
                TransferWire.writeMessage(
                    out,
                    JSONObject().put("cmd", "imgs").put("app", APP)
                        .put("from", selfId)
                        .put("nonce", nonce)
                        .put("ts", stamp)
                        .put("auth", auth)
                        .put("paths", JSONArray(missing.toList())),
                )
                val input = BufferedInputStream(s.getInputStream())
                var total = 0L
                while (true) {
                    val line = TransferWire.readLine(input) ?: break
                    // 头部只是路径与长度，超过这个尺寸的一定不是正常对端
                    if (line.length > 4 * 1024) break
                    val header = runCatching { JSONObject(line) }.getOrNull() ?: break
                    if (header.optBoolean("done")) break
                    if (!header.optBoolean("ok")) continue
                    val path = normalizeImagePath(header.optString("path"))
                    val size = header.optLong("size", 0L)
                    // 只接受加密帧：旧协议的明文图片一律不落盘
                    if (header.optString("enc") != "aes-gcm") break
                    if (path.isEmpty() || !path.startsWith("images/") ||
                        size <= 0 || size > MAX_IMAGE_BYTES + 1024
                    ) {
                        break
                    }
                    if (total + size > MAX_IMAGE_TOTAL) break
                    val buffer = java.io.ByteArrayOutputStream(size.toInt().coerceAtMost(8 * 1024 * 1024))
                    TransferWire.copyExactly(input, buffer, size)
                    val plain = Crypto.decrypt(trust.secret, buffer.toByteArray()) ?: break
                    if (plain.size > MAX_IMAGE_BYTES) break
                    val file = ImageStore.imageFile(context, path)
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".part")
                    try {
                        tmp.outputStream().use { it.write(plain) }
                        if (tmp.renameTo(file)) total += plain.size else tmp.delete()
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }
            }
        }
    }

    /** 对端来取图：逐个路径回「header + 加密字节」，最后一条 done。 */
    private fun sendImages(context: Context, req: JSONObject, socket: Socket, secret: String) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val paths = req.optJSONArray("paths") ?: JSONArray()
        var sent = 0
        // 单次请求的路径数也要封顶：否则一条请求就能让本机连续读几十次磁盘
        val limit = minOf(paths.length(), MAX_IMAGE_FILES)
        for (i in 0 until limit) {
            val raw = paths.optString(i)
            val safe = normalizeImagePath(raw)
            if (safe.isEmpty() || !safe.startsWith("images/")) {
                TransferWire.writeMessage(
                    out, JSONObject().put("ok", false).put("path", raw),
                )
                continue
            }
            val file = ImageStore.imageFile(context, safe)
            if (!file.exists() || file.length() <= 0 || file.length() > MAX_IMAGE_BYTES) {
                TransferWire.writeMessage(
                    out, JSONObject().put("ok", false).put("path", safe),
                )
                continue
            }
            val rawBytes = runCatching { file.readBytes() }.getOrNull() ?: continue
            // 图片常含发票/证件/家庭照，只在加密后出网卡
            val encrypted = Crypto.encrypt(secret, rawBytes) ?: continue
            TransferWire.writeMessage(
                out,
                JSONObject().put("ok", true)
                    .put("path", safe)
                    .put("size", encrypted.size)
                    .put("enc", "aes-gcm"),
            )
            out.write(encrypted)
            out.flush()
            sent++
        }
        TransferWire.writeMessage(out, JSONObject().put("done", true).put("count", sent))
        out.flush()
    }

    private fun itemsJson(items: List<ExpiryItem>): JSONArray = JSONArray(items.map { it.toJson() })

    private fun parseItems(arr: JSONArray?): List<ExpiryItem> {
        if (arr == null) return emptyList()
        val out = ArrayList<ExpiryItem>()
        for (i in 0 until arr.length()) {
            ExpiryItem.fromJson(arr.opt(i))?.let { out += it }
        }
        return out
    }

    /** 与手动分享共用一个设备 id（transfer/deviceId），避免两套身份。 */
    private fun deviceId(context: Context): String {
        val prefs = context.getSharedPreferences("transfer", Context.MODE_PRIVATE)
        val existing = prefs.getString("deviceId", null)
        if (!existing.isNullOrBlank()) return existing
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString("deviceId", id).apply()
        return id
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }.distinct().joinToString(" ").ifBlank { "Android 设备" }

    private fun timeNow(): String = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    // ---------------------------------------------------------------- 定时兜底

    /** 开机或设置变化后按开关恢复 30 分钟同步排程；开关关闭时取消。 */
    fun scheduleIfEnabled(context: Context) {
        val app = context.applicationContext
        appContext = app
        val settings = SettingsStore.get()
        if (settings.autoSyncEnabled.value && settings.autoSyncOnBoot.value) scheduleAlarm(app)
        else cancelAlarm(app)
    }

    fun scheduleAlarm(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + ALARM_INTERVAL_MS,
            pendingIntent(context),
        )
    }

    private fun cancelAlarm(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        manager.cancel(pendingIntent(context))
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 2001,
        Intent(context, AutoSyncReceiver::class.java).setAction(ACTION_SYNC),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
