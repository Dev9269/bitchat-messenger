package com.bitchat.data

import android.content.Context
import android.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Guards the only path that has ever destroyed user data: when the stored database
 * passphrase could not be decrypted, the old code minted a fresh one and overwrote the
 * stored copy, leaving the existing database permanently unopenable — which the open
 * path then "resolved" by deleting the database outright.
 */
@RunWith(RobolectricTestRunner::class)
class DbPassphraseTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs
        get() = context.getSharedPreferences("bitchat_vault", Context.MODE_PRIVATE)

    @Before
    fun resetVault() {
        prefs.edit().clear().commit()
    }

    @Test
    fun freshInstall_mintsAndThenReusesTheSamePassphrase() {
        val first = DataGraph.dbPassphrase(context)
        assertEquals(32, first.size)
        assertNotNull(prefs.getString("db_passphrase", null))

        val second = DataGraph.dbPassphrase(context)
        assertArrayEquals("stored passphrase must round-trip", first, second)
    }

    @Test
    fun undecryptableStoredPassphrase_isRefusedRatherThanReplaced() {
        val garbage = ByteArray(32) { 0x5A.toByte() }
        prefs.edit()
            .putString("db_passphrase", Base64.encodeToString(garbage, Base64.NO_WRAP))
            .commit()
        val before = prefs.getString("db_passphrase", null)

        assertThrows(
            "an undecryptable passphrase must surface as an error, not a silent remint",
            DataGraph.DatabaseOpenException::class.java
        ) {
            DataGraph.dbPassphrase(context)
        }

        assertEquals(
            "the stored passphrase must be left untouched so the data stays recoverable",
            before,
            prefs.getString("db_passphrase", null)
        )
    }
}
