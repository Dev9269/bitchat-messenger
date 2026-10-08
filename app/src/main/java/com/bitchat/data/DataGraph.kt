package com.bitchat.data

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.bitchat.crypto.KeystoreVault
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

object DataGraph {

    /**
     * Non-null when the encrypted database could not be opened. Nothing that touches
     * [repository] may run while it is set; the database file on disk has been left
     * exactly as it was and the UI has to say so rather than carry on as if empty.
     */
    var openFailure: String? = null
        private set

    lateinit var database: AppDatabase
        private set

    val repository: Repository by lazy { Repository(database) }

    /** Raised when the encrypted database cannot be opened. Never deletes anything. */
    class DatabaseOpenException(message: String) : Exception(message)

    fun init(context: Context) {
        openFailure = null
        if (isRobolectric()) {
            database = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME).build()
            return
        }
        try {
            database = openEncrypted(context)
        } catch (e: Exception) {
            openFailure = "Ghostwire could not unlock its local database" +
                (e.message?.let { ": $it" } ?: "") + ". " +
                "Your messages have NOT been deleted and are still on this device. " +
                "If this keeps happening, reinstall the app and restore your account " +
                "with your recovery key."
        }
    }

    private fun isRobolectric(): Boolean =
        android.os.Build.FINGERPRINT.contains("robolectric")

    private fun openEncrypted(context: Context): AppDatabase {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
            .openHelperFactory(SupportFactory(dbPassphrase(context)))
            .build()
        db.openHelper.writableDatabase
        return db
    }

    internal fun dbPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(DB_PASS_KEY, null)
        if (stored != null) {
            // Never mint a replacement. An undecryptable passphrase means the database is
            // still there and still sealed; overwriting the stored copy would lock it away
            // forever, which is what made the old code delete it "to recover".
            return KeystoreVault.decrypt(Base64.decode(stored, Base64.NO_WRAP))
                ?: throw DatabaseOpenException("the stored passphrase could not be decrypted")
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blob = KeystoreVault.encrypt(pass)
        prefs.edit()
            .putString(DB_PASS_KEY, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
        return pass
    }

    private const val DB_NAME = "bitchat.db"
    private const val VAULT_PREFS = "bitchat_vault"
    private const val DB_PASS_KEY = "db_passphrase"
}