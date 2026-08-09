package com.bitchat.data

import android.content.Context
import android.util.Base64
import androidx.room.Room
import com.bitchat.crypto.KeystoreVault
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom

object DataGraph {

    lateinit var database: AppDatabase
        private set

    val repository: Repository by lazy { Repository(database) }

    fun init(context: Context) {
        if (isRobolectric()) {
            database = Room.databaseBuilder(context, AppDatabase::class.java, "bitchat.db")
                .fallbackToDestructiveMigration()
                .build()
            return
        }
        val factory = SupportFactory(dbPassphrase(context))
        database = tryOpen(context, factory)
            ?: run {
                context.deleteDatabase("bitchat.db")
                tryOpen(context, factory)!!
            }
    }

    private fun isRobolectric(): Boolean =
        android.os.Build.FINGERPRINT.contains("robolectric")

    private fun tryOpen(context: Context, factory: SupportFactory): AppDatabase? = try {
        Room.databaseBuilder(context, AppDatabase::class.java, "bitchat.db")
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
            .also { it.openHelper.writableDatabase }
    } catch (_: Exception) {
        null
    }

    private fun dbPassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(VAULT_PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(DB_PASS_KEY, null)
        if (stored != null) {
            KeystoreVault.decrypt(Base64.decode(stored, Base64.NO_WRAP))?.let { return it }
        }
        val pass = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val blob = KeystoreVault.encrypt(pass)
        prefs.edit()
            .putString(DB_PASS_KEY, Base64.encodeToString(blob, Base64.NO_WRAP))
            .apply()
        return pass
    }

    private const val VAULT_PREFS = "bitchat_vault"
    private const val DB_PASS_KEY = "db_passphrase"
}