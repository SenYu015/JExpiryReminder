package com.jimmy.expiryreminder.core

import com.jimmy.expiryreminder.core.Crypto.toHex
import java.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 配对握手（v2）的密码学部分。
 *
 * 旧协议把长期信任密钥（PeerTrust 的 secret）以明文 JSON 回传，同一 Wi-Fi 下
 * 抓一次包就能永久冒充对方并解密后续同步载荷。v2 改为：
 *
 * 1. 双方各生成一对临时 EC P-256 密钥，公钥走明文（公开信息）；
 * 2. 各自用 ECDH 算出同一份共享密钥；
 * 3. 会话密钥 = HKDF-SHA256(共享密钥, salt = SHA-256(双方公钥 + 4 位配对码))，
 *    把配对码和公钥都绑进密钥，配对码仍需两台屏幕人眼比对；
 * 4. 配对响应（token + 长期 secret + 设备名/id）用 AES-256-GCM 加密后再回传。
 *
 * 被动抓包者只有两把临时公钥，没有私钥就算不出 ECDH 共享密钥，也就算不出会话密钥，
 * 拿不到 secret。临时密钥一次性使用，事后丢弃。
 *
 * 残余风险：能主动劫持 TCP 并替换公钥的中间人，可以分别与两端各算一份 ECDH，
 * 并因为配对码是明文传输而通过人眼比对。要彻底堵住需要 PAKE（SPAKE2/OPAQUE），
 * 复杂度与「两台自己的设备、同一 Wi-Fi」的场景不相称，这里明确不做。
 */
object PairCrypto {

    /** 协议版本，服务端用它拒绝不支持加密握手的旧客户端。 */
    const val VERSION = 2

    private const val CURVE = "secp256r1"
    private const val KEY_ALG = "EC"
    private const val KDF_INFO = "jer-pair-payload"
    private const val TRANSCRIPT_PREFIX = "JER-PAIR-V2|"
    private const val KEY_BYTES = 32

    private val rng = SecureRandom()

    fun newKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(KEY_ALG).apply {
            initialize(ECGenParameterSpec(CURVE), rng)
        }.generateKeyPair()

    /** 公钥走 X.509 编码 + Base64，两端都能直接解析。 */
    fun publicKeyBase64(keyPair: KeyPair): String = encodePublic(keyPair.public)

    fun encodePublic(key: PublicKey): String = Base64.getEncoder().encodeToString(key.encoded)

    /**
     * 用本机私钥 + 对方公钥算 ECDH 共享密钥，再按 [pin] 与双方公钥派生会话密钥。
     * 任一步失败（公钥非法、曲线不匹配）返回 null，调用方按配对失败处理。
     */
    fun deriveKey(
        privateKey: PrivateKey,
        peerPublicBase64: String,
        clientPublicBase64: String,
        serverPublicBase64: String,
        pin: String,
    ): String? = runCatching {
        val peer = decodePublic(peerPublicBase64)
        val agreement = KeyAgreement.getInstance("ECDH")
        agreement.init(privateKey)
        agreement.doPhase(peer, true)
        val shared = agreement.generateSecret()
        require(shared.size >= KEY_BYTES) { "ECDH 共享密钥长度异常" }
        // salt 把两端公钥与配对码绑进密钥：换掉公钥或用错配对码都会派生出不同密钥
        val transcript = TRANSCRIPT_PREFIX + clientPublicBase64 + "|" + serverPublicBase64 + "|" + pin
        val salt = sha256(transcript.toByteArray(Charsets.UTF_8))
        hkdfSha256(shared, salt, KDF_INFO.toByteArray(Charsets.UTF_8), KEY_BYTES).toHex()
    }.getOrNull()

    /** 加密配对响应；返回 `IV(12B) || ciphertext+tag` 的 hex。 */
    fun encryptPayload(keyHex: String, payload: JSONObject): String? =
        Crypto.encrypt(keyHex, payload.toString().toByteArray(Charsets.UTF_8))?.toHex()

    /** 解密对端发来的响应；密钥不对 / 被篡改 / 格式非法都返回 null。 */
    fun decryptPayload(keyHex: String, boxHex: String): JSONObject? {
        val blob = Crypto.unhex(boxHex) ?: return null
        val plain = Crypto.decrypt(keyHex, blob) ?: return null
        return runCatching { JSONObject(String(plain, Charsets.UTF_8)) }.getOrNull()
    }

    private fun decodePublic(base64: String): PublicKey {
        val bytes = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance(KEY_ALG).generatePublic(X509EncodedKeySpec(bytes))
    }

    private fun sha256(data: ByteArray): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256").digest(data)

    /** RFC 5869 HKDF-SHA256（extract + expand）。 */
    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val out = ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (out.size() < length) {
            mac.update(block)
            mac.update(info)
            mac.update(counter.toByte())
            block = mac.doFinal()
            out.write(block)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }
}
