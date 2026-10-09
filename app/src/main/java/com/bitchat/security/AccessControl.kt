package com.bitchat.security

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Session-style access gating.
 *
 * - Personal chat (DMs) is hidden for everyone until either the node shows
 *   up on the owner's allowlist (server-side check done while connected) or
 *   the user enters the owner's master secret (verified against the SHA-256
 *   hash stored in settings/access; the raw secret never touches this device).
 * - Groups: one active group at a time. Joining a non-default group always
 *   requires that group's secret code.
 */
object AccessControl {

    const val DEFAULT_GROUP_ID = "ghostwire-default"

    private const val PREFS = "bitchat_access"
    private const val KEY_DM_UNLOCKED = "dm_unlocked"
    private const val PBKDF2_ITERATIONS = 120_000
    private const val SECRET_KEY_BITS = 256
    const val SECRET_SALT_BYTES = 16

    private val _dmUnlocked = MutableStateFlow(false)
    val dmUnlocked: StateFlow<Boolean> = _dmUnlocked.asStateFlow()

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _dmUnlocked.value = prefs!!.getBoolean(KEY_DM_UNLOCKED, false)
    }

    fun isDmUnlocked(): Boolean = _dmUnlocked.value

    /** Called after verifying the secret against settings/access (or allowlist hit). */
    fun setDmUnlocked(unlocked: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DM_UNLOCKED, unlocked)?.apply()
        _dmUnlocked.value = unlocked
    }

    fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** A fresh random salt for a stored secret verifier. */
    fun newSecretSalt(): ByteArray = ByteArray(SECRET_SALT_BYTES).also { SecureRandom().nextBytes(it) }

    /**
     * Derives a verifier for a master secret with PBKDF2-HMAC-SHA256. Stored verifiers are
     * public (settings/access is readable by any session), so a salt plus a slow KDF is what
     * keeps the secret from being recovered offline - a bare SHA-256 is not enough.
     */
    fun deriveSecretKey(secret: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, PBKDF2_ITERATIONS, SECRET_KEY_BITS)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}