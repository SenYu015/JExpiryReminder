package com.jimmy.expiryreminder.core

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 大文件（手动分享的 zip）的流式加解密：AES-256-CTR + HMAC-SHA256。
 *
 * 为什么不用 GCM：物品包可达 64MB，GCM 需要整块进内存，加解密时峰值会到 3 倍包体，
 * 中端机直接 OOM。CTR 是流式的，内存恒定；完整性由尾部 HMAC 兜底
 * （先校验再解密，避免把篡改内容写进本地）。
 *
 * 文件格式：`IV(16B) || ciphertext || HMAC(32B)`，HMAC 覆盖 IV 与密文。
 * 注意这是加密传输，不是签名：密钥来自设备配对，双方共享。
 */
object StreamCrypto {

    private const val IV_BYTES = 16
    private const val TAG_BYTES = 32
    private const val BUF = 64 * 1024

    private val rng = SecureRandom()

    /** 加密 [input] 写入 [output]；成功返回 true（失败时调用方应删除输出）。 */
    fun encryptTo(input: File, output: File, secretHex: String): Boolean = runCatching {
        val key = Crypto.unhex(secretHex) ?: return false
        if (key.size != 32) return false
        val iv = ByteArray(IV_BYTES).also(rng::nextBytes)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        FileOutputStream(output).use { raw ->
            raw.write(iv)
            CipherOutputStream(raw, cipher).use { cos ->
                FileInputStream(input).use { it.copyTo(cos, BUF) }
            }
        }
        // 密文写完后追加 HMAC；覆盖 IV + 密文，防止 IV 被替换
        val mac = macFor(secretHex) ?: return false
        FileInputStream(output).use { fin ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = fin.read(buf)
                if (n < 0) break
                mac.update(buf, 0, n)
            }
        }
        FileOutputStream(output, true).use { it.write(mac.doFinal()) }
        true
    }.getOrDefault(false)

    /** 校验并解密 [input] 到 [output]；校验失败返回 false 且不写输出内容。 */
    fun decryptTo(input: File, output: File, secretHex: String): Boolean = runCatching {
        val key = Crypto.unhex(secretHex) ?: return false
        if (key.size != 32) return false
        val total = input.length()
        if (total <= IV_BYTES + TAG_BYTES) return false

        val mac = macFor(secretHex) ?: return false
        val tag = ByteArray(TAG_BYTES)
        FileInputStream(input).use { fin ->
            val buf = ByteArray(BUF)
            var remaining = total - TAG_BYTES
            while (remaining > 0) {
                val want = minOf(buf.size.toLong(), remaining).toInt()
                val n = fin.read(buf, 0, want)
                if (n < 0) return false
                mac.update(buf, 0, n)
                remaining -= n
            }
            var read = 0
            while (read < TAG_BYTES) {
                val n = fin.read(tag, read, TAG_BYTES - read)
                if (n < 0) return false
                read += n
            }
        }
        if (!MessageDigest.isEqual(mac.doFinal(), tag)) return false

        FileInputStream(input).use { fin ->
            val iv = ByteArray(IV_BYTES)
            var read = 0
            while (read < IV_BYTES) {
                val n = fin.read(iv, read, IV_BYTES - read)
                if (n < 0) return false
                read += n
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            // 只解密 IV 与 HMAC 之间的密文
            val body = CipherInputStream(LimitedInputStream(fin, total - IV_BYTES - TAG_BYTES), cipher)
            FileOutputStream(output).use { out -> body.use { it.copyTo(out, BUF) } }
        }
        true
    }.getOrDefault(false)

    private fun macFor(secretHex: String): Mac? {
        val key = Crypto.unhex(secretHex) ?: return null
        // 与 AES 密钥分开派生，避免同一把密钥身兼两职
        val macKey = MessageDigest.getInstance("SHA-256")
            .digest(key + "jexpiry-mac".toByteArray(Charsets.UTF_8))
        return runCatching {
            Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(macKey, "HmacSHA256")) }
        }.getOrNull()
    }

    /** 只放行前 [limit] 字节，避免把尾部 HMAC 也送进解密器。 */
    private class LimitedInputStream(
        private val source: FileInputStream,
        private var limit: Long,
    ) : java.io.InputStream() {
        override fun read(): Int {
            if (limit <= 0) return -1
            val b = source.read()
            if (b >= 0) limit -= 1
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (limit <= 0) return -1
            val want = minOf(len.toLong(), limit).toInt()
            val n = source.read(b, off, want)
            if (n > 0) limit -= n
            return n
        }

        override fun close() {
            // 关由上层 CipherInputStream 负责，这里不动 source
        }
    }
}
