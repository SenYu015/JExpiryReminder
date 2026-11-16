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
        // 兜底闹钟也要过「开机自动同步」这一关：关掉时不排，进程被杀后就不会再被拉起
        scheduleIfEnabled(context)
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
        while (isActive) {
            val client = runCatching { socket.accept() }.getOrNull() ?: break
            launch { handleClient(client, context) }
        }
    }

    /** 对方连进来：读它的快照 → 合并 → 回一份合并后的本机快照。 */
    private suspend fun handleClient(client: Socket, context: Context) {
        client.use { s ->
            s.soTimeout = 10_000
            val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                .readLine() ?: return
            if (line.length > MAX_LINE) return
            val req = runCatching { JSONObject(line) }.getOrNull() ?: return
            if (req.optString("app") != APP) return
            if (req.optString("cmd") == "imgs") {
                sendImages(context, req, s)
                return
            }
            if (req.optString("cmd") != "sync") return
            val peerId = req.optString("from")
            val sinceRev = req.optLong("sinceRev", 0L)
            val remote = parseItems(req.optJSONArray("items"))
            val remoteMeta = MetaSnapshot.fromJson(req.optJSONObject("meta"))
            val remoteImages = parseImageIndex(req.optJSONObject("imageIndex"))
            val from = req.optString("name").ifBlank { peerId }
            // 分类/空间可能单独变化（物品没动），所以无条件走一次归并
            applyRemote(context, remote, from.ifBlank { "另一台设备" }, remoteMeta, remoteImages)
            // 对方声称已收到我发出的 <= sinceRev 的记录；同时推进「我从对方收到的」水位
            if (peerId.isNotBlank()) {
                SyncStateStore.update(
                    context, peerId,
                    recvMax = SyncDelta.maxRev(remote),
                    sentMax = sinceRev,
                )
            }
            // 只回对方没见过的增量；两边都没改动时就是一次空握手，不写库
            val outgoing = ExpiryStore.get().itemsSince(sinceRev)
            val resp = JSONObject()
                .put("ok", true)
                .put("from", deviceId(context))
                .put("name", deviceName())
                .put("items", itemsJson(outgoing))
                .put("meta", localMeta().toJson())
                .put("imageIndex", imageIndexJson())
                .put("ackRev", SyncDelta.maxRev(remote))
                .put("revNow", ExpiryStore.get().revision)
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writer.write(resp.toString() + "\n")
            writer.flush()
        }
    }

    private suspend fun tick(context: Context) {
        val list = _peers.value
        if (list.isEmpty()) return
        for (peer in list) {
            val last = lastSyncAt[peer.id] ?: 0L
            if (System.currentTimeMillis() - last < PEER_COOLDOWN_MS) continue
            lastSyncAt[peer.id] = System.currentTimeMillis()
            val result = runCatching {
                val remote = exchange(peer.host, peer.port, context, peer.id)
                applyRemote(context, remote.items, peer.name, remote.meta, remote.imageIndex)
                fetchMissingImages(context, peer.host, peer.port)
            }
            _status.value = if (result.isSuccess) {
                "已开启 · ${timeNow()} 已同步 ${list.size} 台设备"
            } else {
                "同步失败：${result.exceptionOrNull()?.message ?: "连接不上"}"
            }
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
        var lastError: String? = null
        for (peer in list) {
            runCatching {
                val remote = exchange(peer.host, peer.port, app, peer.id)
                applyRemote(app, remote.items, peer.name, remote.meta, remote.imageIndex)
                fetchMissingImages(app, peer.host, peer.port)
            }.onSuccess {
                ok++
                lastSyncAt[peer.id] = System.currentTimeMillis()
            }.onFailure { lastError = it.message }
        }
        val text = if (ok > 0) "已与 $ok 台设备同步" else "同步失败：${lastError ?: "连接不上"}"
        _status.value = "已开启 · ${timeNow()} $text"
        return text
    }

    private suspend fun exchange(host: String, port: Int, context: Context, peerId: String): RemotePayload {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), 6_000)
            s.soTimeout = 12_000
            val state = SyncStateStore.peer(context, peerId)
            // 只发「对方还没确认收到」的变更；无变更时 items 为空
            val outgoing = ExpiryStore.get().itemsSince(state.sentRev)
            val req = JSONObject()
                .put("cmd", "sync")
                .put("app", APP)
                .put("schema", 4)
                .put("from", deviceId(context))
                .put("name", deviceName())
                .put("sinceRev", state.recvRev)
                .put("sentRev", state.sentRev)
                .put("meta", localMeta().toJson())
                .put("imageIndex", imageIndexJson())
                .put("items", itemsJson(outgoing))
            val writer = OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8)
            writer.write(req.toString() + "\n")
            writer.flush()
            val line = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                .readLine() ?: error("对方没有返回数据")
            if (line.length > MAX_LINE) error("返回数据过大")
            val resp = JSONObject(line)
            if (!resp.optBoolean("ok")) error(resp.optString("error").ifBlank { "对方拒绝了同步" })
            val remote = parseItems(resp.optJSONArray("items"))
            SyncStateStore.update(
                context, peerId,
                recvMax = SyncDelta.maxRev(remote),
                sentMax = resp.optLong("ackRev", 0L),
            )
            // 把本机时钟抬到对方水位，保证之后本机编辑的 rev 一定更大
            ExpiryStore.get().observeRemoteClock(context, resp.optLong("revNow", 0L))
            return RemotePayload(
                items = remote,
                meta = MetaSnapshot.fromJson(resp.optJSONObject("meta")),
                imageIndex = parseImageIndex(resp.optJSONObject("imageIndex")),
            )
        }
    }

    private data class RemotePayload(
        val items: List<ExpiryItem>,
        val meta: MetaSnapshot,
        val imageIndex: Map<String, List<String>>,
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
    ): SyncPlan {
        val store = ExpiryStore.get()
        // 先归并分类/空间，再把远端物品上的引用改到本机 id，最后才做物品合并
        val incoming = if (remoteMeta != null) {
            val metaPlan = MetaSync.plan(localMeta(), remoteMeta)
            store.applyMetaSync(context, metaPlan)
            MetaSync.remap(remote, metaPlan)
        } else remote
        store.observeRemoteClock(context, SyncDelta.maxRev(incoming))
        val plan = AutoSyncMerge.plan(store.items, incoming, from, SyncStateStore.syncedIds(context))
        store.applyAutoSyncItems(context, plan.adds, plan.updates)
        // 本机较新的记录抬 rev，下一轮增量会把它回传给对方完成收敛
        if (plan.touchIds.isNotEmpty()) store.bumpRevForSync(context, plan.touchIds)
        if (plan.adds.isNotEmpty() || plan.updates.isNotEmpty() || plan.touchIds.isNotEmpty()) {
            SyncStateStore.markSynced(
                context,
                plan.adds.map { it.id } + plan.updates.map { it.id } + plan.touchIds,
            )
        }
        val newConflicts = ConflictStore.add(context, plan.conflicts)
        if (newConflicts > 0) {
            ReminderService.showNotification(
                context, NOTIFICATION_ID,
                "有 $newConflicts 件物品需要确认",
                "两台设备各自录入了疑似重复物品，点开后到「设置 → 待合并冲突」手动合并",
            )
        }
        // 给本地缺图的物品挂上对端引用，文件由本轮/下一轮 fetchMissingImages 拉取
        if (remoteImageIndex.isNotEmpty()) repairImageAttachments(context, remoteImageIndex)
        return plan
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
    private fun fetchMissingImages(context: Context, host: String, port: Int) {
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

        runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 6_000)
                s.soTimeout = 20_000
                val out = BufferedOutputStream(s.getOutputStream())
                TransferWire.writeMessage(
                    out,
                    JSONObject().put("cmd", "imgs").put("app", APP)
                        .put("paths", JSONArray(missing.toList())),
                )
                val input = BufferedInputStream(s.getInputStream())
                var total = 0L
                while (true) {
                    val line = TransferWire.readLine(input) ?: break
                    val header = runCatching { JSONObject(line) }.getOrNull() ?: break
                    if (header.optBoolean("done")) break
                    if (!header.optBoolean("ok")) continue
                    val path = normalizeImagePath(header.optString("path"))
                    val size = header.optLong("size", 0L)
                    if (path.isEmpty() || !path.startsWith("images/") ||
                        size <= 0 || size > MAX_IMAGE_BYTES
                    ) {
                        break
                    }
                    if (total + size > MAX_IMAGE_TOTAL) break
                    val file = ImageStore.imageFile(context, path)
                    file.parentFile?.mkdirs()
                    val tmp = File(file.parentFile, file.name + ".part")
                    try {
                        tmp.outputStream().use { TransferWire.copyExactly(input, it, size) }
                        if (tmp.renameTo(file)) total += size else tmp.delete()
                    } catch (e: Exception) {
                        tmp.delete()
                        throw e
                    }
                }
            }
        }
    }

    /** 对端来取图：逐个路径回「header + 原始字节」，最后一条 done。 */
    private fun sendImages(context: Context, req: JSONObject, socket: Socket) {
        val out = BufferedOutputStream(socket.getOutputStream())
        val paths = req.optJSONArray("paths") ?: JSONArray()
        var sent = 0
        for (i in 0 until paths.length()) {
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
            TransferWire.writeMessage(
                out, JSONObject().put("ok", true).put("path", safe).put("size", file.length()),
            )
            file.inputStream().use { TransferWire.copyExactly(it, out, file.length()) }
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
