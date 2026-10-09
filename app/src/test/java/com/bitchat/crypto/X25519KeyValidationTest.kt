package com.bitchat.crypto

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * A low-order (small-subgroup) X25519 public key drives the shared secret to all zeroes for
 * any private key. BouncyCastle refuses the agreement by throwing, but the app accepted any
 * 32-byte payload from a handshake or the cloud and stored it as a peer key - a later DM send
 * to that peer then threw mid-coroutine. Peer keys must be rejected at the point we accept
 * them.
 */
@RunWith(RobolectricTestRunner::class)
class X25519KeyValidationTest {

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
    fun theAllZeroLowOrderKeyIsRejected() {
        assertFalse(CryptoEngine.isUsableX25519PublicKey(ByteArray(32)))
    }

    @Test
    fun aWellFormedPublicKeyIsAccepted() {
        assertTrue(CryptoEngine.isUsableX25519PublicKey(CryptoEngine.x25519PublicKey()))
    }

    @Test
    fun aKeyOfTheWrongLengthIsRejected() {
        assertFalse(CryptoEngine.isUsableX25519PublicKey(ByteArray(31)))
    }
}
