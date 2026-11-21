package com.jimmy.expiryreminder

import com.jimmy.expiryreminder.core.PairCrypto
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 配对握手 v2：两端用各自临时私钥 + 对方公钥 + 同一个配对码派生会话密钥，
 * 服务端加密回传的 token/secret 必须只有持有私钥的客户端能解开。
 */
class PairCryptoTest {

    private val pin = "0427"

    @Test
    fun bothSidesDeriveSameKey() {
        val client = PairCrypto.newKeyPair()
        val server = PairCrypto.newKeyPair()
        val clientPub = PairCrypto.publicKeyBase64(client)
        val serverPub = PairCrypto.publicKeyBase64(server)

        val clientKey = PairCrypto.deriveKey(client.private, serverPub, clientPub, serverPub, pin)
        val serverKey = PairCrypto.deriveKey(server.private, clientPub, clientPub, serverPub, pin)

        assertNotNull(clientKey)
        assertEquals(clientKey, serverKey)
    }

    @Test
    fun payloadOnlyDecryptsWithMatchingPinAndKey() {
        val client = PairCrypto.newKeyPair()
        val server = PairCrypto.newKeyPair()
        val clientPub = PairCrypto.publicKeyBase64(client)
        val serverPub = PairCrypto.publicKeyBase64(server)

        val serverKey = PairCrypto.deriveKey(server.private, clientPub, clientPub, serverPub, pin)
            ?: error("server key")
        val payload = JSONObject()
            .put("token", "tok-123")
            .put("secret", "a".repeat(64))
            .put("name", "手机 B")
            .put("id", "dev-b")
        val box = PairCrypto.encryptPayload(serverKey, payload) ?: error("box")

        // 客户端用同样的 ECDH + pin 解开
        val clientKey = PairCrypto.deriveKey(client.private, serverPub, clientPub, serverPub, pin)
            ?: error("client key")
        val plain = PairCrypto.decryptPayload(clientKey, box)
        assertNotNull(plain)
        assertEquals("tok-123", plain!!.optString("token"))
        assertEquals("a".repeat(64), plain.optString("secret"))
        assertEquals("dev-b", plain.optString("id"))

        // 配对码不一致 → 派生出不同密钥 → 解不开（GCM 鉴权失败）
        val wrongKey = PairCrypto.deriveKey(client.private, serverPub, clientPub, serverPub, "0000")
        assertNotNull(wrongKey)
        assertNotEquals(clientKey, wrongKey)
        assertNull(PairCrypto.decryptPayload(wrongKey!!, box))
    }

    @Test
    fun thirdPartyWithoutPrivateKeyCannotDeriveKey() {
        val client = PairCrypto.newKeyPair()
        val server = PairCrypto.newKeyPair()
        val clientPub = PairCrypto.publicKeyBase64(client)
        val serverPub = PairCrypto.publicKeyBase64(server)

        val eavesdropper = PairCrypto.newKeyPair()
        // 抓包者只看到两把公钥；拿自己的私钥去算，得到的是完全不同的密钥
        val clientKey = PairCrypto.deriveKey(client.private, serverPub, clientPub, serverPub, pin)
            ?: error("client key")
        val attacker = PairCrypto.deriveKey(eavesdropper.private, serverPub, clientPub, serverPub, pin)
        assertNotNull(attacker)
        assertNotEquals(clientKey, attacker)

        val serverKey = PairCrypto.deriveKey(server.private, clientPub, clientPub, serverPub, pin)
            ?: error("server key")
        val box = PairCrypto.encryptPayload(
            serverKey,
            JSONObject().put("secret", "s".repeat(64)),
        ) ?: error("box")
        assertNull(PairCrypto.decryptPayload(attacker!!, box))
    }
}
