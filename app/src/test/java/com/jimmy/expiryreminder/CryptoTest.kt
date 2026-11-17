package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.Crypto
import com.jimmy.expiryreminder.core.IoLimits
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class CryptoTest {

    @Test
    fun `secret is 64 hex chars and unique`() {
        val a = Crypto.newSecret()
        val b = Crypto.newSecret()
        assertEquals(64, a.length)
        assertNotEquals(a, b)
        assertEquals(32, Crypto.unhex(a)!!.size)
    }

    @Test
    fun `hmac is deterministic and key dependent`() {
        val secret = Crypto.newSecret()
        val other = Crypto.newSecret()
        assertEquals(Crypto.hmacHex(secret, "sync|a|b|1"), Crypto.hmacHex(secret, "sync|a|b|1"))
        assertNotEquals(Crypto.hmacHex(secret, "sync|a|b|1"), Crypto.hmacHex(other, "sync|a|b|1"))
    }

    @Test
    fun `hmac rejects malformed secret`() {
        assertNull(Crypto.hmacHex("zz", "x"))
        assertNull(Crypto.hmacHex("", "x"))
    }

    @Test
    fun `constant time equals only matches identical non empty strings`() {
        assertTrue(Crypto.constantTimeEquals("abc", "abc"))
        assertFalse(Crypto.constantTimeEquals("abc", "abd"))
        assertFalse(Crypto.constantTimeEquals(null, "abc"))
        assertFalse(Crypto.constantTimeEquals("", ""))
    }

    @Test
    fun `encrypt decrypt round trip`() {
        val secret = Crypto.newSecret()
        val plain = "物品名称，含中文与 emoji 🥛".toByteArray(Charsets.UTF_8)
        val blob = Crypto.encrypt(secret, plain)!!
        assertArrayEquals(plain, Crypto.decrypt(secret, blob))
    }

    @Test
    fun `decrypt fails on wrong key or tampered blob`() {
        val secret = Crypto.newSecret()
        val blob = Crypto.encrypt(secret, "hello".toByteArray())!!
        assertNull(Crypto.decrypt(Crypto.newSecret(), blob))
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertNull(Crypto.decrypt(secret, tampered))
        assertNull(Crypto.decrypt(secret, ByteArray(4)))
    }

    @Test
    fun `unhex rejects odd length and non hex`() {
        assertNull(Crypto.unhex("abc"))
        assertNull(Crypto.unhex("zz"))
        assertEquals(1, Crypto.unhex("ff")!!.size)
    }
}

class IoLimitsTest {

    @Test
    fun `readLimited returns payload under limit`() {
        val data = ByteArray(1024) { 7 }
        val out = IoLimits.readLimited(ByteArrayInputStream(data), 2048)
        assertArrayEquals(data, out)
    }

    @Test(expected = IoLimits.TooLargeException::class)
    fun `readLimited throws when payload exceeds limit`() {
        IoLimits.readLimited(ByteArrayInputStream(ByteArray(4096)), 1024)
    }

    @Test
    fun `limits leave room for compressed images but block zip bombs`() {
        assertTrue(IoLimits.MAX_BACKUP_TOTAL > IoLimits.MAX_BACKUP_ENTRY)
        assertTrue(IoLimits.MAX_IMAGE_TOTAL > IoLimits.MAX_IMAGE_ENTRY)
        assertTrue(IoLimits.TooLargeException("x") is IOException)
    }
}
