package com.bitchat.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The personal-chat master secret is verified against a value stored in the world-readable
 * settings/access document. Storing a bare SHA-256 of it let anyone read the document and
 * crack the secret offline. A per-document salt plus a slow KDF makes that infeasible.
 */
@RunWith(RobolectricTestRunner::class)
class AccessControlSecretTest {

    @Test
    fun aDerivedKeyIsDeterministicForTheSameSalt() {
        val salt = ByteArray(16) { it.toByte() }

        assertArrayEquals(
            AccessControl.deriveSecretKey("hunter2", salt),
            AccessControl.deriveSecretKey("hunter2", salt)
        )
    }

    @Test
    fun differentSaltsProduceDifferentKeys() {
        val k1 = AccessControl.deriveSecretKey("hunter2", ByteArray(16) { 1 })
        val k2 = AccessControl.deriveSecretKey("hunter2", ByteArray(16) { 2 })

        assertFalse(k1.contentEquals(k2))
    }

    @Test
    fun theWrongSecretDoesNotMatchTheStoredKey() {
        val salt = ByteArray(16) { 3 }
        val stored = AccessControl.deriveSecretKey("the-right-one", salt)
        val attempt = AccessControl.deriveSecretKey("guess", salt)

        assertFalse(AccessControl.constantTimeEquals(stored, attempt))
    }
}
