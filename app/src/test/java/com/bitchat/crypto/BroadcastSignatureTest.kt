package com.bitchat.crypto

import android.content.Context
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A broadcast carries a signed payload plus the signer's ed25519 public key, and a node id is
 * first16(SHA-256(that key)). Verification must reject any packet whose embedded key does not
 * hash to the node id the packet claims. Otherwise an attacker mints a fresh signature over
 * the victim's node id using their own key, embeds their own key, and is accepted as the
 * victim - which is exactly what happened before node ids were bound to keys.
 */
@RunWith(RobolectricTestRunner::class)
class BroadcastSignatureTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before
    fun reset() {
        context.getSharedPreferences("bitchat_keys", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("bitchat_recovery", Context.MODE_PRIVATE)
            .edit().clear().commit()
        CryptoEngine.init(context)
    }

    @Test
    fun aBroadcastVerifiesForTheSignersOwnNodeId() {
        val text = "hello mesh".toByteArray(Charsets.UTF_8)
        val sender = CryptoEngine.nodeId()
        val signed = CryptoEngine.signBroadcast(sender, text)

        assertArrayEquals(text, CryptoEngine.verifyBroadcast(sender, signed))
    }

    @Test
    fun aPeerCannotMintABroadcastForAnotherNodeId() {
        val victim = "cc".repeat(16)
        val forged = CryptoEngine.signBroadcast(victim, "i am the victim".toByteArray(Charsets.UTF_8))

        assertNull(
            "a signature keyed by our own key must not be accepted as the victim",
            CryptoEngine.verifyBroadcast(victim, forged)
        )
    }

    @Test
    fun aBroadcastIsRejectedWhenTheClaimedNodeIdDiffersFromTheSigner() {
        val sender = CryptoEngine.nodeId()
        val signed = CryptoEngine.signBroadcast(sender, "hello".toByteArray(Charsets.UTF_8))

        assertNull(CryptoEngine.verifyBroadcast("bb".repeat(16), signed))
    }

    @Test
    fun tamperedTextIsRejected() {
        val sender = CryptoEngine.nodeId()
        val signed = CryptoEngine.signBroadcast(sender, "hello".toByteArray(Charsets.UTF_8))
        signed[signed.lastIndex] = (signed.last().toInt() xor 0x01).toByte()

        assertNull(CryptoEngine.verifyBroadcast(sender, signed))
    }
}
