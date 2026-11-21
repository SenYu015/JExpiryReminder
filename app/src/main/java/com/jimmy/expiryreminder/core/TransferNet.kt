package com.jimmy.expiryreminder.core

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** mDNS 服务类型：只在本机局域网广播，不经过任何服务器。 */
const val TRANSFER_SERVICE_TYPE = "_jexpiry._tcp."

/** 固定端口，方便「手动输入 IP」兜底；被占用时退回随机端口（此时只能靠自动发现）。 */
const val TRANSFER_PREFERRED_PORT = 47821

private const val PAIR_TIMEOUT_MS = 90_000L
private const val CONNECT_TIMEOUT_MS = 8_000
private const val SEND_TIMEOUT_MS = 120_000
/** 空间同步要等对方选空间 + 双方各自合并，给的时间比单向发送长一些。 */
private const val SYNC_TIMEOUT_MS = 180_000L

/** 同一 IP 的配对尝试限速：防止脚本刷弹窗、赌用户手滑点「接受」。 */
private const val PAIR_RATE_WINDOW_MS = 60_000L
private const val PAIR_RATE_MAX = 3

/** 同时在处理的连接数上限，避免局域网里有人开一堆 socket 把线程与内存吃满。 */
private const val MAX_CLIENTS = 8

/** 传输载荷加密格式标识（AES-CTR + HMAC-SHA256，见 [StreamCrypto]）。 */
private const val ENC_STREAM = "aes-ctr-hmac"

/**
 * 极简的行分隔 JSON 协议：每条消息一行 JSON，二进制载荷跟在 header 后面按 size 精确读取。
 * 两台设备都是自己人，不需要 HTTP 那套解析，也顺带绕开明文流量策略。
 */
object TransferWire {

    private const val MAX_HEADER = 8 * 1024

    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("UTF-8")
            if (b == '\n'.code) return buf.toString("UTF-8")
            if (b == '\r'.code) continue
            if (buf.size() >= MAX_HEADER) throw IOException("消息头过长")
            buf.write(b)
        }
    }

    fun writeMessage(out: OutputStream, json: JSONObject) {
        out.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 精确读 [size] 字节写进 [out]；对端提前断开时抛 [IOException]。 */
    fun copyExactly(input: InputStream, out: OutputStream, size: Long) {
        val buf = ByteArray(64 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException("连接中断，数据不完整")
            out.write(buf, 0, n)
            left -= n
        }
    }
}

/**
 * 接收端服务：监听本机端口，处理配对请求与物品包。
 *
 * 配对：对端发来 4 位配对码 + 临时公钥 → 本机弹出确认（用户比对两台屏幕上的数字）→
 * 同意后用 ECDH + 配对码派生的密钥把「一次性 token + 长期信任密钥」加密回传（见 [PairCrypto]）。
 * 长期密钥永不明文过网，之后传输必须带 token。token 只存在内存里，离开传输页即失效。
 */
class TransferServer(
    private val context: Context,
    private val deviceId: String,
    private val deviceName: String,
) {

    data class PairRequest(
        val pin: String,
        val fromName: String,
        val fromId: String,
        /** 用户点「接受 / 拒绝」后调用。 */
        val respond: (Boolean) -> Unit,
    )

    /** 已收进本地缓存、等用户选空间的批次。 */
    data class Incoming(
        val id: String,
        val sender: String,
        val sentAt: String,
        val items: List<ExpiryItem>,
        val zip: File,
    )

    /** 对方发起的空间同步：等本机用户选空间并确认。 */
    data class SyncOffer(
        val id: String,
        val sender: String,
        val spaceLabel: String,
        val items: List<ExpiryItem>,
        val zip: File,
        /** 传本地空间 id 表示接受；[spaceId] 为 null 表示拒绝。 */
        val respond: (String?) -> Unit,
    )

    private val _syncOffer = MutableStateFlow<SyncOffer?>(null)
    val syncOffer: StateFlow<SyncOffer?> = _syncOffer.asStateFlow()

    private val _port = MutableStateFlow(0)
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _pairRequest = MutableStateFlow<PairRequest?>(null)
    val pairRequest: StateFlow<PairRequest?> = _pairRequest.asStateFlow()

    private val _incoming = MutableStateFlow<List<Incoming>>(emptyList())
    val incoming: StateFlow<List<Incoming>> = _incoming.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val tokens = ConcurrentHashMap<String, String>() // token -> 对端 deviceId
    private val pendingPair = AtomicReference<CompletableDeferred<Boolean>?>(null)
    private val pendingSync = AtomicReference<CompletableDeferred<String?>?>(null)
    private val clientSlots = Semaphore(MAX_CLIENTS)
    /** ip -> 最近几次配对尝试的时间戳；只在 [allowPairAttempt] 里读写。 */
    private val pairAttempts = HashMap<String, MutableList<Long>>()

    fun start(): Int {
        serverSocket?.let { return _port.value }
        val socket = try {
            ServerSocket(TRANSFER_PREFERRED_PORT)
        } catch (_: Exception) {
            ServerSocket(0)
        }
        serverSocket = socket
        _port.value = socket.localPort
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                launch {
                    clientSlots.withPermit { handle(client) }
                }
            }
        }
        return socket.localPort
    }

    fun stop() {
        runCatching { acceptJob?.cancel() }
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptJob = null
        _port.value = 0
        pendingPair.getAndSet(null)?.complete(false)
        pendingSync.getAndSet(null)?.complete(null)
        _pairRequest.value = null
        _syncOffer.value = null
        _incoming.value = emptyList()
        tokens.clear()
        synchronized(pairAttempts) { pairAttempts.clear() }
        // 未收录的批次离开页面即作废，顺手清掉缓存文件，免得 cache 越攒越大
        runCatching {
            TransferPayload.inboxDir(context).listFiles()?.forEach { it.delete() }
        }
        scope.cancel()
    }

    /** 从待接收列表里移除（已收录或用户丢弃）。 */
    fun takeIncoming(id: String): Incoming? {
        val batch = _incoming.value.firstOrNull { it.id == id } ?: return null
        _incoming.update { list -> list.filterNot { it.id == id } }
        return batch
    }

    /** 用户对同步请求作出决定：传空间 id = 接受，null = 拒绝。 */
    fun respondSync(spaceId: String?) {
        _syncOffer.value?.respond?.invoke(spaceId)
        _syncOffer.value = null
    }

    private suspend fun handle(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = SEND_TIMEOUT_MS
                val input = BufferedInputStream(socket.getInputStream())
                val output = BufferedOutputStream(socket.getOutputStream())
                val header = TransferWire.readLine(input) ?: return
                val msg = runCatching { JSONObject(header) }.getOrNull() ?: return
                when (msg.optString("cmd")) {
                    "ping" -> TransferWire.writeMessage(
                        output,
                        JSONObject().put("ok", true).put("name", deviceName).put("id", deviceId),
                    )
                    "pair" -> handlePair(socket, output, msg)
                    "send" -> handleSend(input, output, msg)
                    "sync" -> handleSync(input, output, msg)
                    else -> TransferWire.writeMessage(
                        output, JSONObject().put("ok", false).put("error", "未知指令"),
                    )
                }
            } catch (_: Exception) {
                // 对端断开 / 超时：不影响本机
            }
        }
    }

    /**
     * 同一 IP 的配对限速。配对弹窗是整条链路上唯一的「人肉确认」，
     * 不限速的话邻居可以反复弹窗碰用户误触。
     */
    private fun allowPairAttempt(ip: String): Boolean {
        val now = System.currentTimeMillis()
        synchronized(pairAttempts) {
            val list = pairAttempts.getOrPut(ip) { mutableListOf() }
            list.removeAll { now - it > PAIR_RATE_WINDOW_MS }
            if (list.size >= PAIR_RATE_MAX) return false
            list.add(now)
            return true
        }
    }

    private suspend fun handlePair(socket: Socket, output: OutputStream, msg: JSONObject) {
        val ip = socket.inetAddress?.hostAddress ?: "unknown"
        if (!allowPairAttempt(ip)) {
            TransferWire.writeMessage(
                output, JSONObject().put("ok", false).put("error", "请求过于频繁，请稍后再试"),
            )
            return
        }
        if (pendingPair.get() != null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方正忙，请稍后重试"))
            return
        }
        val pin = msg.optString("pin")
        val fromName = msg.optString("device").ifBlank { "另一台设备" }
        val fromId = msg.optString("deviceId")
        // 设备标识是自动同步信任表的键，缺失就无法把这次配对记成长期信任，直接拒绝
        if (fromId.isBlank()) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方设备标识缺失"))
            return
        }
        // v2 起配对码只用于人眼比对，长期密钥改走 ECDH + pin 派生的 AES-GCM 密文回传；
        // 旧客户端不支持加密握手，宁可拒绝也不能退回明文，否则抓包照样能拿到密钥
        val peerVer = msg.optInt("ver", 0)
        val peerPub = msg.optString("pub")
        if (peerVer < PairCrypto.VERSION || peerPub.isBlank()) {
            TransferWire.writeMessage(
                output,
                JSONObject().put("ok", false).put("error", "对方版本过旧，请两台设备都升级后再配对"),
            )
            return
        }
        val deferred = CompletableDeferred<Boolean>()
        pendingPair.set(deferred)
        _pairRequest.value = PairRequest(
            pin = pin,
            fromName = fromName,
            fromId = fromId,
            respond = { accept ->
                // 只允许「当前这个请求」被响应，避免旧弹窗误提交
                if (pendingPair.compareAndSet(deferred, null)) {
                    _pairRequest.value = null
                    deferred.complete(accept)
                }
            },
        )
        val accepted = withTimeoutOrNull(PAIR_TIMEOUT_MS) { deferred.await() } ?: false
        pendingPair.set(null)
        _pairRequest.value = null

        if (!accepted) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方拒绝了配对"))
            return
        }
        val token = UUID.randomUUID().toString().replace("-", "")
        tokens[token] = fromId
        // 长期信任密钥：重复配对复用原值，避免把对方手上的旧密钥顶掉
        val secret = PeerTrust.ensure(context, fromId, fromName)
        // 临时密钥对只用于这一次握手，算完即弃
        val keyPair = runCatching { PairCrypto.newKeyPair() }.getOrNull()
        val myPub = keyPair?.let { PairCrypto.publicKeyBase64(it) }
        val sessionKey = if (keyPair != null && myPub != null) {
            PairCrypto.deriveKey(
                privateKey = keyPair.private,
                peerPublicBase64 = peerPub,
                clientPublicBase64 = peerPub,
                serverPublicBase64 = myPub,
                pin = pin,
            )
        } else {
            null
        }
        // 密钥派生或加密失败都必须明确失败：绝不能把 secret 退回明文
        val box = sessionKey?.let {
            PairCrypto.encryptPayload(
                it,
                JSONObject()
                    .put("token", token)
                    .put("secret", secret)
                    .put("name", deviceName)
                    .put("id", deviceId),
            )
        }
        if (box == null) {
            // 握手失败：把刚登记的 token 撤回，别在内存里留一张没发出去的通行证
            tokens.remove(token)
            TransferWire.writeMessage(
                output, JSONObject().put("ok", false).put("error", "配对握手失败，请重试"),
            )
            return
        }
        TransferWire.writeMessage(
            output,
            JSONObject()
                .put("ok", true)
                .put("ver", PairCrypto.VERSION)
                .put("pub", myPub)
                .put("box", box),
        )
    }

    private fun handleSend(input: InputStream, output: OutputStream, msg: JSONObject) {
        val token = msg.optString("token")
        val fromId = tokens[token]
        if (fromId == null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "配对已失效，请重新配对"))
            return
        }
        // 物品包一律加密：图片里常有发票、证件、家庭照，不能让同网设备直接嗅到
        val trust = PeerTrust.of(context, fromId)
        if (trust == null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "未配对设备，请重新配对"))
            return
        }
        if (msg.optString("enc") != ENC_STREAM) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方版本过旧，请升级后重试"))
            return
        }
        val size = msg.optLong("size")
        if (size <= 0 || size > TransferPayload.MAX_BYTES + 1024) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "物品包为空或超出大小上限"))
            return
        }
        val stamp = System.currentTimeMillis()
        val enc = File(TransferPayload.inboxDir(context), "in-$stamp.enc")
        val zip = File(TransferPayload.inboxDir(context), "in-$stamp.zip")
        enc.outputStream().buffered().use { out ->
            TransferWire.copyExactly(input, out, size)
        }
        if (!StreamCrypto.decryptTo(enc, zip, trust.secret)) {
            enc.delete()
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "物品包校验失败，可能被篡改"))
            return
        }
        enc.delete()
        val batch = TransferPayload.readManifest(zip)
        if (batch == null || batch.items.isEmpty()) {
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "物品包无法解析"))
            return
        }
        _incoming.update {
            it + Incoming(
                id = uid(),
                sender = batch.sender,
                sentAt = batch.sentAt,
                items = batch.items,
                zip = zip,
            )
        }
        // 先告诉对方「已送达」；本机用户选完空间才算真正收录
        TransferWire.writeMessage(output, JSONObject().put("ok", true).put("count", batch.items.size))
    }

    /**
     * 空间同步（接收侧）：收下对方快照 → 等用户选本机空间 → 合并 → 把本机该空间的
     * 合并后快照回传。对方收到后再合并一次，两边收敛成同一份。
     */
    private suspend fun handleSync(input: InputStream, output: OutputStream, msg: JSONObject) {
        val token = msg.optString("token")
        val fromId = tokens[token]
        if (fromId == null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "配对已失效，请重新配对"))
            return
        }
        val trust = PeerTrust.of(context, fromId)
        if (trust == null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "未配对设备，请重新配对"))
            return
        }
        if (msg.optString("enc") != ENC_STREAM) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方版本过旧，请升级后重试"))
            return
        }
        if (pendingSync.get() != null) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方正忙，请稍后重试"))
            return
        }
        val size = msg.optLong("size")
        if (size <= 0 || size > TransferPayload.MAX_BYTES + 1024) {
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "同步包为空或超出大小上限"))
            return
        }
        val stamp = System.currentTimeMillis()
        val enc = File(TransferPayload.inboxDir(context), "sync-$stamp.enc")
        val zip = File(TransferPayload.inboxDir(context), "sync-$stamp.zip")
        enc.outputStream().buffered().use { out -> TransferWire.copyExactly(input, out, size) }
        if (!StreamCrypto.decryptTo(enc, zip, trust.secret)) {
            enc.delete()
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "同步包校验失败，可能被篡改"))
            return
        }
        enc.delete()
        val batch = TransferPayload.readManifest(zip)
        if (batch == null) {
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "同步包无法解析"))
            return
        }

        val deferred = CompletableDeferred<String?>()
        pendingSync.set(deferred)
        _syncOffer.value = SyncOffer(
            id = uid(),
            sender = batch.sender,
            spaceLabel = msg.optString("space").ifBlank { "未命名空间" },
            items = batch.items,
            zip = zip,
            respond = { spaceId ->
                if (pendingSync.compareAndSet(deferred, null)) {
                    _syncOffer.value = null
                    deferred.complete(spaceId)
                }
            },
        )

        val targetSpace = withTimeoutOrNull(SYNC_TIMEOUT_MS) { deferred.await() }
        pendingSync.set(null)
        _syncOffer.value = null
        if (targetSpace == null) {
            zip.delete()
            TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "对方拒绝了同步"))
            return
        }

        val store = ExpiryStore.get()
        val plan = SpaceSync.plan(store.items, batch.items, targetSpace)
        val applied = SpaceSync.apply(context, store, zip, batch.items, targetSpace, plan)
        zip.delete()

        // 回传合并后本机该空间的快照，对方据此补齐自己缺的那部分
        val snapshot = store.itemsInSpace(targetSpace)
        val reply = TransferPayload.writeZip(context, snapshot, deviceName)
        val replyEnc = File(TransferPayload.inboxDir(context), "reply-${System.currentTimeMillis()}.enc")
        try {
            if (!StreamCrypto.encryptTo(reply, replyEnc, trust.secret)) {
                TransferWire.writeMessage(output, JSONObject().put("ok", false).put("error", "回传包加密失败"))
                return
            }
            TransferWire.writeMessage(
                output,
                JSONObject()
                    .put("ok", true)
                    .put("added", applied.added)
                    .put("updated", applied.overwritten)
                    .put("count", snapshot.size)
                    .put("size", replyEnc.length())
                    .put("enc", ENC_STREAM),
            )
            replyEnc.inputStream().buffered().use { it.copyTo(output) }
            output.flush()
        } finally {
            reply.delete()
            replyEnc.delete()
        }
    }
}

/** 局域网设备发现（mDNS）。只负责发现，不传数据。 */
class TransferDiscovery(
    context: Context,
    private val deviceId: String,
    private val deviceName: String,
) {

    data class Peer(
        val serviceName: String,
        val deviceId: String,
        val name: String,
        val host: String,
        val port: Int,
    )

    /**
     * 发现状态。以前这里失败是静默的：`runCatching` 吞掉异常、回调只置一个内部 flag，
     * 页面永远显示「正在搜索…」，用户分不清是没设备、被路由器挡了、还是系统根本不让发现。
     * 现在把失败原因直接交给 UI。
     */
    data class State(
        val running: Boolean = false,
        /** 面向用户的失败原因；null 表示正常。 */
        val error: String? = null,
    )

    private val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var started = false
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    /** 已经解析过（或正在解析）的服务名，避免同一台设备被反复 resolve。 */
    private val seen = ConcurrentHashMap.newKeySet<String>()
    /** resolveService 并发调用会 FAILURE_ALREADY_ACTIVE，这里串成队列逐个来。 */
    private val resolveQueue = java.util.ArrayDeque<NsdServiceInfo>()
    private val resolvingOne = java.util.concurrent.atomic.AtomicBoolean(false)
    /** 每个服务的解析失败次数：mDNS 多播丢包很常见，失败几次再放弃。 */
    private val resolveFailures = ConcurrentHashMap<String, Int>()
    /** 延迟重试用的作用域，随 stop() 一起取消。 */
    private var scope: CoroutineScope? = null

    fun start(port: Int) {
        if (started) return
        val manager = nsd
        if (manager == null) {
            _state.value = State(
                running = false,
                error = "系统没有提供局域网发现服务（NsdManager 不可用），只能用「手动连接」输入对方地址",
            )
            return
        }
        started = true
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        _state.value = State(running = true)

        val info = NsdServiceInfo().apply {
            serviceName = "JEX-${deviceId.take(8)}"
            serviceType = TRANSFER_SERVICE_TYPE
            setPort(port)
            setAttribute("id", deviceId)
            setAttribute("name", deviceName)
        }
        val registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                _state.update { it.copy(error = null) }
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // 广播失败不影响本机搜索，但对方永远搜不到这台设备，必须说出来
                _state.update {
                    it.copy(error = "本机服务未能广播（错误码 $errorCode），对方可能搜不到这台设备")
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = registration
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
        }.onFailure { e ->
            _state.update { it.copy(error = "本机服务注册失败：${describe(e)}") }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                _state.update { it.copy(running = true, error = null) }
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType != TRANSFER_SERVICE_TYPE) return
                if (!seen.add(serviceInfo.serviceName)) return
                enqueueResolve(manager, serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                seen.remove(serviceInfo.serviceName)
                resolveFailures.remove(serviceInfo.serviceName)
                _peers.update { list -> list.filterNot { it.serviceName == serviceInfo.serviceName } }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                started = false
                _state.value = State(
                    running = false,
                    error = "局域网发现启动失败（错误码 $errorCode），可点「重试搜索」或改用「手动连接」",
                )
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        discoveryListener = discovery
        runCatching {
            manager.discoverServices(TRANSFER_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery)
        }.onFailure { e ->
            started = false
            _state.value = State(
                running = false,
                error = "局域网发现启动失败：${describe(e)}，可点「重试搜索」或改用「手动连接」",
            )
        }
    }

    private fun describe(e: Throwable): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    private fun enqueueResolve(manager: NsdManager, info: NsdServiceInfo) {
        synchronized(resolveQueue) { resolveQueue.addLast(info) }
        drainResolve(manager)
    }

    private fun drainResolve(manager: NsdManager) {
        if (!resolvingOne.compareAndSet(false, true)) return
        val next = synchronized(resolveQueue) { resolveQueue.pollFirst() }
        if (next == null) {
            resolvingOne.set(false)
            return
        }
        fun finish() {
            resolvingOne.set(false)
            drainResolve(manager)
        }
        @Suppress("DEPRECATION")
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                finish()
                retryResolveLater(manager, info, errorCode)
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                resolveFailures.remove(info.serviceName)
                addPeer(info)
                finish()
            }
        }
        val started = runCatching {
            @Suppress("DEPRECATION")
            manager.resolveService(next, listener)
        }.isSuccess
        if (!started) {
            finish()
            retryResolveLater(manager, next, -1)
        }
    }

    /**
     * 解析失败重试：mDNS 多播丢包、路由器限速都会导致偶发失败，
     * 以前一次失败就永久丢掉这台设备（`seen` 里还留着名字，后续 found 也不会再解析）。
     */
    private fun retryResolveLater(manager: NsdManager, info: NsdServiceInfo, errorCode: Int) {
        val attempts = (resolveFailures[info.serviceName] ?: 0) + 1
        if (attempts > MAX_RESOLVE_ATTEMPTS) {
            resolveFailures.remove(info.serviceName)
            // 放开去重，让下一次广播重新尝试；否则这台设备这辈子都不会再出现
            seen.remove(info.serviceName)
            return
        }
        resolveFailures[info.serviceName] = attempts
        scope?.launch {
            delay(600L * attempts)
            if (started) enqueueResolve(manager, info)
        }
    }

    private fun addPeer(info: NsdServiceInfo) {
        // API 34+ 一次能拿到全部地址；只取单个 host 时经常是 IPv6 link-local（fe80::），
        // 不带 scopeId 直连必超时——这里统一 IPv4 优先。
        val addresses = if (Build.VERSION.SDK_INT >= 34) {
            info.hostAddresses
        } else {
            @Suppress("DEPRECATION")
            listOfNotNull(info.host)
        }
        val host = (addresses.filterIsInstance<Inet4Address>().firstOrNull()
            ?: addresses.firstOrNull())?.hostAddress ?: return
        val port = info.port
        if (port <= 0) return
        val attrs = info.attributes.orEmpty()
        val id = attrs["id"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: info.serviceName
        if (id == deviceId) return // 自己
        val name = attrs["name"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() } ?: info.serviceName
        val peer = Peer(info.serviceName, id, name, host, port)
        _peers.update { list ->
            val index = list.indexOfFirst { it.deviceId == id }
            if (index >= 0) list.toMutableList().also { it[index] = peer } else list + peer
        }
    }

    fun stop() {
        val manager = nsd ?: return
        discoveryListener?.let { listener -> runCatching { manager.stopServiceDiscovery(listener) } }
        registrationListener?.let { listener -> runCatching { manager.unregisterService(listener) } }
        discoveryListener = null
        registrationListener = null
        started = false
        seen.clear()
        resolveFailures.clear()
        synchronized(resolveQueue) { resolveQueue.clear() }
        resolvingOne.set(false)
        scope?.cancel()
        scope = null
        _peers.value = emptyList()
        _state.value = State()
    }

    private companion object {
        /** 单个服务最多解析几次（含首次）。 */
        const val MAX_RESOLVE_ATTEMPTS = 3
    }
}

/** 发送端：配对 + 上传物品包。 */
object TransferClient {

    data class Paired(
        val token: String,
        val deviceName: String,
        val deviceId: String,
        /** 长期信任密钥（hex），用于自动同步的 HMAC 认证与载荷加密。 */
        val secret: String,
    )

    suspend fun pair(
        host: String,
        port: Int,
        pin: String,
        deviceName: String,
        deviceId: String,
    ): Result<Paired> = runCatching {
        // 临时密钥对：只在本握手内使用，公钥随请求发出，私钥不外传
        val keyPair = PairCrypto.newKeyPair()
        val myPub = PairCrypto.publicKeyBase64(keyPair)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = (PAIR_TIMEOUT_MS + 10_000).toInt()
            val out = BufferedOutputStream(socket.getOutputStream())
            TransferWire.writeMessage(
                out,
                JSONObject()
                    .put("cmd", "pair")
                    .put("pin", pin)
                    .put("device", deviceName)
                    .put("deviceId", deviceId)
                    .put("ver", PairCrypto.VERSION)
                    .put("pub", myPub),
            )
            val line = TransferWire.readLine(BufferedInputStream(socket.getInputStream()))
                ?: throw IOException("对方没有响应")
            val json = JSONObject(line)
            if (!json.optBoolean("ok")) {
                throw IOException(json.optStringOrNull("error") ?: "配对失败")
            }
            // 响应必须是 v2 密文；旧版对端会直接回明文 secret，这里一律不认
            if (json.optInt("ver", 0) < PairCrypto.VERSION) {
                throw IOException("对方版本过旧，请两台设备都升级后再配对")
            }
            val peerPub = json.optString("pub")
            val box = json.optString("box")
            if (peerPub.isBlank() || box.isBlank()) throw IOException("配对响应格式不正确")
            val key = PairCrypto.deriveKey(
                privateKey = keyPair.private,
                peerPublicBase64 = peerPub,
                clientPublicBase64 = myPub,
                serverPublicBase64 = peerPub,
                pin = pin,
            ) ?: throw IOException("配对握手失败，请重试")
            val payload = PairCrypto.decryptPayload(key, box)
                ?: throw IOException("配对响应校验失败，配对码可能不一致")
            Paired(
                token = payload.optString("token"),
                deviceName = payload.optString("name").ifBlank { "对方设备" },
                deviceId = payload.optString("id"),
                secret = payload.optString("secret"),
            )
        }
    }

    /** 上传 zip（先加密）；[onProgress] 回传已发送字节数。返回对方确认收到的件数。 */
    suspend fun send(
        host: String,
        port: Int,
        token: String,
        zip: File,
        count: Int,
        secret: String,
        onProgress: (Long) -> Unit,
    ): Result<Int> = runCatching {
        val enc = File(zip.parentFile, zip.name + ".enc")
        try {
            if (!StreamCrypto.encryptTo(zip, enc, secret)) throw IOException("物品包加密失败")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = SEND_TIMEOUT_MS
                val out = BufferedOutputStream(socket.getOutputStream())
                TransferWire.writeMessage(
                    out,
                    JSONObject()
                        .put("cmd", "send")
                        .put("token", token)
                        .put("count", count)
                        .put("size", enc.length())
                        .put("enc", ENC_STREAM),
                )
                enc.inputStream().buffered().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
                out.flush()
                val line = TransferWire.readLine(BufferedInputStream(socket.getInputStream()))
                    ?: throw IOException("对方没有响应")
                val json = JSONObject(line)
                if (!json.optBoolean("ok")) {
                    throw IOException(json.optStringOrNull("error") ?: "发送失败")
                }
                json.optInt("count", count)
            }
        } finally {
            enc.delete()
        }
    }

    data class SyncReply(val added: Int, val updated: Int, val count: Int, val zip: File)

    /**
     * 发起空间同步：上传本机快照 → 对方合并后回传它的快照 → 落到 [replyDir] 交给调用方合并。
     * [onProgress] 只反映上传阶段。
     */
    suspend fun sync(
        host: String,
        port: Int,
        token: String,
        zip: File,
        count: Int,
        secret: String,
        spaceLabel: String,
        replyDir: File,
        onProgress: (Long) -> Unit,
    ): Result<SyncReply> = runCatching {
        val enc = File(zip.parentFile, zip.name + ".enc")
        try {
            if (!StreamCrypto.encryptTo(zip, enc, secret)) throw IOException("同步包加密失败")
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                // 比服务端的确认超时再宽一点，避免两边同时放弃
                socket.soTimeout = (SYNC_TIMEOUT_MS + 30_000).toInt()
                val out = BufferedOutputStream(socket.getOutputStream())
                TransferWire.writeMessage(
                    out,
                    JSONObject()
                        .put("cmd", "sync")
                        .put("token", token)
                        .put("space", spaceLabel)
                        .put("count", count)
                        .put("size", enc.length())
                        .put("enc", ENC_STREAM),
                )
                enc.inputStream().buffered().use { input ->
                    val buf = ByteArray(64 * 1024)
                    var sent = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sent += n
                        onProgress(sent)
                    }
                }
                out.flush()

                val input = BufferedInputStream(socket.getInputStream())
                val line = TransferWire.readLine(input) ?: throw IOException("对方没有响应")
                val json = JSONObject(line)
                if (!json.optBoolean("ok")) {
                    throw IOException(json.optStringOrNull("error") ?: "同步失败")
                }
                if (json.optString("enc") != ENC_STREAM) throw IOException("对方版本过旧，请升级后重试")
                val size = json.optLong("size")
                if (size < 0 || size > TransferPayload.MAX_BYTES + 1024) {
                    throw IOException("回传包大小异常")
                }
                replyDir.mkdirs()
                val stamp = System.currentTimeMillis()
                val encReply = File(replyDir, "sync-reply-$stamp.enc")
                val file = File(replyDir, "sync-reply-$stamp.zip")
                encReply.outputStream().buffered().use { TransferWire.copyExactly(input, it, size) }
                if (!StreamCrypto.decryptTo(encReply, file, secret)) {
                    encReply.delete()
                    file.delete()
                    throw IOException("回传包校验失败，可能被篡改")
                }
                encReply.delete()
                SyncReply(
                    added = json.optInt("added"),
                    updated = json.optInt("updated"),
                    count = json.optInt("count"),
                    zip = file,
                )
            }
        } finally {
            enc.delete()
        }
    }
}
