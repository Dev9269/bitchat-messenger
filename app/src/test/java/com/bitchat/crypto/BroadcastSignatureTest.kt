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
 * A broadcast carries a signed payload plus the signer's ed25519 public key. The signature
 * used to cover only the message text, so the public key was never tied to the node id the
 * packet claimed to be from. An attacker could take a broadcast signed by Alice and relay it
 * with the header's src rewritten to Bob; recipients verified the text against Alice's key
 * and stored it as if Bob had sent it.
 *
 * Node ids are SHA-256(seed), not a function of the ed25519 key, so a receiver cannot derive
 * the binding - it has to be signed.
 */
@RunWith(RobolectricTestRunner::class)
class BroadcastSignatureTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val alice = "aa".repeat(16)
    private val bob = "bb".repeat(16)

    @Before
    fun reset() {
        context.getSharedPreferences("bitchat_keys", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("bitchat_recovery", Context.MODE_PRIVATE)
            .edit().clear().commit()
        CryptoEngine.init(context)
    }

    @Test
    fun aSignatureIsOnlyValidForTheNodeIdItWasSignedFor() {
        val text = "hello mesh".toByteArray(Charsets.UTF_8)
        val signedByAlice = CryptoEngine.signBroadcast(alice, text)

        assertArrayEquals(
            "the sender's own node id must still verify",
            text,
            CryptoEngine.verifyBroadcast(alice, signedByAlice)
        )
        assertNull(
            "a signature made for one node id must not verify as another",
            CryptoEngine.verifyBroadcast(bob, signedByAlice)
        )
    }

    @Test
    fun tamperedTextIsRejected() {
        val signedByAlice = CryptoEngine.signBroadcast(alice, "hello".toByteArray(Charsets.UTF_8))
        signedByAlice[signedByAlice.lastIndex] =
            (signedByAlice.last().toInt() xor 0x01).toByte()

        assertNull(CryptoEngine.verifyBroadcast(alice, signedByAlice))
    }
}
