package com.bitchat.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A peer's x25519 key is trusted-on-first-use and pinned: once stored it never changes. This
 * is what makes DM sender authentication hold - a DM only decrypts under the sender's key, and
 * that key is both identity-bound (see KeyBindingTest) and frozen at first sight.
 */
class PeerKeyPinTest {

    @Test
    fun aKeyIsAdoptedWhenNoneIsStored() {
        assertTrue(CryptoEngine.shouldAdoptPeerKey(null, ByteArray(32) { 1 }))
    }

    @Test
    fun anIdenticalKeyIsAdopted() {
        val key = ByteArray(32) { 2 }
        assertTrue(CryptoEngine.shouldAdoptPeerKey(key.copyOf(), key))
    }

    @Test
    fun aDifferentKeyIsRejected() {
        val existing = ByteArray(32) { 1 }
        val replacement = ByteArray(32) { 2 }
        assertFalse(CryptoEngine.shouldAdoptPeerKey(existing, replacement))
    }
}
