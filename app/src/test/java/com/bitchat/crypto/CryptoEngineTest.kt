package com.bitchat.crypto

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CryptoEngineTest {

    private lateinit var ctx: Context

    // Arbitrary peer X25519 public key (32 bytes). X25519 DH is symmetric, so
    // encrypt-with-peerPub and decrypt-with-peerPub round-trip within one engine.
    private val peerPub: ByteArray = ByteArray(32) { (it * 3 + 1).toByte() }

    @Before
    fun setUp() {
        ctx = RuntimeEnvironment.getApplication()
        CryptoEngine.init(ctx)
    }

    @Test
    fun dmEncryptDecryptRoundTrip() {
        val msgId = byteArrayOf(1, 2, 3, 4)
        val plaintext = "hello world".toByteArray()
        val env = CryptoEngine.encryptDM(peerPub, msgId, plaintext)
        assertNotNull(env)
        val dec = CryptoEngine.decryptDM(peerPub, msgId, env)
        assertNotNull(dec)
        assertArrayEquals(plaintext, dec)
    }

    @Test
    fun dmTamperFails() {
        val msgId = byteArrayOf(9, 9, 9, 9)
        val plaintext = "secret".toByteArray()
        val env = CryptoEngine.encryptDM(peerPub, msgId, plaintext)
        val tampered = env.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0xFF).toByte() }
        assertNull(CryptoEngine.decryptDM(peerPub, msgId, tampered))
    }

    @Test
    fun groupKeyWrapUnwrapRoundTrip() {
        val groupKey = CryptoEngine.newGroupKey()
        val groupId = "test-group-123"
        val env = CryptoEngine.wrapGroupKey(peerPub, groupId, groupKey)
        assertNotNull(env)
        val unwrapped = CryptoEngine.unwrapGroupKey(peerPub, groupId, env)
        assertNotNull(unwrapped)
        assertArrayEquals(groupKey, unwrapped)
    }

    @Test
    fun groupKeyWrapWrongGroupFails() {
        val groupKey = CryptoEngine.newGroupKey()
        val env = CryptoEngine.wrapGroupKey(peerPub, "group-a", groupKey)
        assertNull(CryptoEngine.unwrapGroupKey(peerPub, "group-b", env))
    }

    @Test
    fun groupMessageEncryptDecryptRoundTrip() {
        val groupKey = CryptoEngine.newGroupKey()
        val msgId = byteArrayOf(7, 7, 7)
        val text = "group secret".toByteArray()
        val ct = CryptoEngine.encryptGroupMessage(groupKey, msgId, text)
        val pt = CryptoEngine.decryptGroupMessage(groupKey, msgId, ct)
        assertNotNull(pt)
        assertArrayEquals(text, pt)
    }

    @Test
    fun broadcastSignVerifyRoundTrip() {
        val text = "broadcast".toByteArray()
        val signed = CryptoEngine.signBroadcast(text)
        val verified = CryptoEngine.verifyBroadcast(signed)
        assertNotNull(verified)
        assertArrayEquals(text, verified)
    }

    @Test
    fun broadcastTamperFails() {
        val text = "broadcast".toByteArray()
        val signed = CryptoEngine.signBroadcast(text)
        val tamperedBytes = signed.copyOf()
        tamperedBytes[8] = (tamperedBytes[8].toInt() xor 0xFF).toByte()
        assertNull(CryptoEngine.verifyBroadcast(tamperedBytes))
    }
}