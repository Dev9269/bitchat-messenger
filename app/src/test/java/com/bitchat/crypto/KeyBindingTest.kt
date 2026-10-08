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
 * A peer's x25519 key must be provably owned by the holder of its node id before it is used
 * to encrypt anything. Otherwise an attacker can present their own x25519 key while claiming
 * a victim's node id (over a handshake, or by writing nodes/{victim}.x_pub in Firestore) and
 * read DMs / hijack group keys addressed to the victim.
 *
 * The binding is ed_pub || x_pub || ed25519-signature over (domain + node_id + x_pub). Since
 * node_id = first16(SHA-256(ed_pub)), a valid binding cannot be produced for an id the signer
 * does not own.
 */
@RunWith(RobolectricTestRunner::class)
class KeyBindingTest {

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
    fun ownKeyBindingVerifiesForOwnNodeId() {
        val xPub = CryptoEngine.extractBoundX25519Pub(CryptoEngine.nodeId(), CryptoEngine.ownKeyBinding())

        assertArrayEquals(CryptoEngine.x25519PublicKey(), xPub)
    }

    @Test
    fun aBindingIsRejectedForADifferentNodeId() {
        val blob = CryptoEngine.ownKeyBinding()

        assertNull(CryptoEngine.extractBoundX25519Pub("cc".repeat(16), blob))
    }

    @Test
    fun aBindingWithATamperedX25519KeyIsRejected() {
        val blob = CryptoEngine.ownKeyBinding()
        blob[32] = (blob[32].toInt() xor 0x01).toByte()

        assertNull(CryptoEngine.extractBoundX25519Pub(CryptoEngine.nodeId(), blob))
    }

    @Test
    fun aBindingWhoseEdKeyDoesNotHashToTheNodeIdIsRejected() {
        val blob = CryptoEngine.ownKeyBinding()
        ByteArray(32) { 9 }.copyInto(blob, 0)

        assertNull(CryptoEngine.extractBoundX25519Pub(CryptoEngine.nodeId(), blob))
    }
}
