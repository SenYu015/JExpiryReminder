package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.Crypto
import com.jimmy.expiryreminder.core.StreamCrypto
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StreamCryptoTest {

    private val dir: File = File(System.getProperty("java.io.tmpdir"), "jex-stream-${System.nanoTime()}")
        .apply { mkdirs() }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sample(size: Int): ByteArray =
        ByteArray(size) { (it * 31 % 251).toByte() }

    @Test
    fun `round trip keeps content intact`() {
        val secret = Crypto.newSecret()
        val plain = File(dir, "a.bin").apply { writeBytes(sample(300_000)) }
        val enc = File(dir, "a.enc")
        val out = File(dir, "a.out")

        assertTrue(StreamCrypto.encryptTo(plain, enc, secret))
        assertTrue(StreamCrypto.decryptTo(enc, out, secret))
        assertArrayEquals(plain.readBytes(), out.readBytes())
        // 密文不应包含明文的连续片段
        assertFalse(enc.readBytes().toString(Charsets.ISO_8859_1).contains("AAAA"))
    }

    @Test
    fun `wrong secret is rejected`() {
        val plain = File(dir, "b.bin").apply { writeBytes(sample(4096)) }
        val enc = File(dir, "b.enc")
        assertTrue(StreamCrypto.encryptTo(plain, enc, Crypto.newSecret()))
        assertFalse(StreamCrypto.decryptTo(enc, File(dir, "b.out"), Crypto.newSecret()))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val secret = Crypto.newSecret()
        val plain = File(dir, "c.bin").apply { writeBytes(sample(8192)) }
        val enc = File(dir, "c.enc")
        assertTrue(StreamCrypto.encryptTo(plain, enc, secret))
        val bytes = enc.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0xFF).toByte()
        enc.writeBytes(bytes)
        assertFalse(StreamCrypto.decryptTo(enc, File(dir, "c.out"), secret))
    }

    @Test
    fun `truncated file is rejected`() {
        val secret = Crypto.newSecret()
        val plain = File(dir, "d.bin").apply { writeBytes(sample(2048)) }
        val enc = File(dir, "d.enc")
        assertTrue(StreamCrypto.encryptTo(plain, enc, secret))
        enc.writeBytes(enc.readBytes().copyOf(20))
        assertFalse(StreamCrypto.decryptTo(enc, File(dir, "d.out"), secret))
    }
}
