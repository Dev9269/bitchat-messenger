package com.bitchat.crypto

import android.content.Context
import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The account identity is derived from stored private keys. If those keys are present but
 * undecryptable, the old code fell through to the "fresh install" branch and generated a
 * brand new identity, silently replacing the user's account. It must instead refuse.
 */
@RunWith(RobolectricTestRunner::class)
class IdentityRecoveryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val keys
        get() = context.getSharedPreferences("bitchat_keys", Context.MODE_PRIVATE)

    @Before
    fun reset() {
        keys.edit().clear().commit()
        context.getSharedPreferences("bitchat_recovery", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun storedKeysThatCannotBeDecrypted_areNotSilentlyReplaced() {
        val undecryptable = "enc1:" +
            Base64.encodeToString(ByteArray(48) { 0x33 }, Base64.NO_WRAP)
        keys.edit().putString("x25519_priv", undecryptable).commit()
        val before = keys.getAll()

        val thrown = try {
            CryptoEngine.init(context)
            null
        } catch (e: CryptoEngine.IdentityUnavailableException) {
            e
        }

        assertNotNull(
            "an undecryptable stored identity must fail loudly, not be regenerated",
            thrown
        )
        assertEquals("the stored keys must be left exactly as they were", before, keys.getAll())
    }

    @Test
    fun freshInstall_stillGeneratesAnIdentity() {
        CryptoEngine.init(context)
        assertTrue(keys.contains("x25519_priv"))
        assertNotNull(CryptoEngine.x25519PublicKey().takeIf { it.isNotEmpty() })
    }
}
