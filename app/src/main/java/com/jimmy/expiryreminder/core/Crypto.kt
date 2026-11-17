package com.jimmy.expiryreminder.core

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 局域网传输用的轻量密码学工具。
 *
 * 只做三件事：生成预共享密钥、HMAC-SHA256 消息认证、AES-256-GCM 载荷加密。
 * 密钥由配对时随机生成并保存在本机（见 [PeerTrust]），不在协议里重复协商，
 * 避免引入 ECDH / 证书链这类与「两台自己的设备、同一 Wi-Fi」场景不相称的复杂度。
 *
 * 所有方法都不抛异常，失败返回 null / false，调用方按「认证不通过」处理。
 */
object Crypto {

    private const val HMAC_ALG = "HmacSHA256"
    private const val AES_ALG = "AES/GCM/NoPadding"
    private const val SECRET_BYTES = 32
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** GCM 的 IV 只要随机且不重复即可；SecureRandom 保证跨设备不撞。 */
    private val rng = SecureRandom()

    fun newSecret(): String = ByteArray(SECRET_BYTES).also(rng::nextBytes).toHex()

    fun randomHex(bytes: Int): String = ByteArray(bytes).also(rng::nextBytes).toHex()

    fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            out.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return out.toString()
    }

    fun unhex(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = hexDigit(hex[i * 2])
            val lo = hexDigit(hex[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /** 消息认证码：[message] 参与认证，[secretHex] 只在本机与对端保存。 */
    fun hmacHex(secretHex: String, message: String): String? {
        val key = unhex(secretHex) ?: return null
        if (key.isEmpty()) return null
        return runCatching {
            val mac = Mac.getInstance(HMAC_ALG)
            mac.init(SecretKeySpec(key, HMAC_ALG))
            mac.doFinal(message.toByteArray(Charsets.UTF_8)).toHex()
        }.getOrNull()
    }

    /** 定长比较，避免按字节短路带来的时序差异。 */
    fun constantTimeEquals(a: String?, b: String?): Boolean {
        if (a.isNullOrEmpty() || b.isNullOrEmpty()) return false
        return MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
    }

    /** 返回 `IV(12B) || ciphertext+tag`；密钥非法时返回 null。 */
    fun encrypt(secretHex: String, plain: ByteArray): ByteArray? {
        val key = unhex(secretHex) ?: return null
        if (key.size != SECRET_BYTES) return null
        return runCatching {
            val iv = ByteArray(IV_BYTES).also(rng::nextBytes)
            val cipher = Cipher.getInstance(AES_ALG)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            iv + cipher.doFinal(plain)
        }.getOrNull()
    }

    /** 解密 [encrypt] 的输出；密钥不对、被篡改、长度不足都返回 null。 */
    fun decrypt(secretHex: String, blob: ByteArray): ByteArray? {
        val key = unhex(secretHex) ?: return null
        if (key.size != SECRET_BYTES || blob.size <= IV_BYTES) return null
        return runCatching {
            val cipher = Cipher.getInstance(AES_ALG)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                GCMParameterSpec(GCM_TAG_BITS, blob, 0, IV_BYTES),
            )
            cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
        }.getOrNull()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
