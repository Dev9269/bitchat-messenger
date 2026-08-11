package com.bitchat.crypto

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Session-style account recovery: the node identity (nodeId + X25519/Ed25519
 * keypairs) is derived deterministically from a 18-byte seed, and the seed is
 * shown to the user exactly once as a checksummed base32 recovery key.
 *
 * Restoring on a new device = paste the key -> same seed -> same identity.
 * No server round-trip, no Firestore rule change needed: possession of the
 * key is possession of the account. The key is never stored in the clear.
 *
 * Known ceiling (see README): profiles/{username} is uid-bound at first
 * claim, so a restored install must pick a fresh online username — Firestore
 * rules cannot verify key possession, and no takeover rule is added.
 * (The mesh identity, E2EE keys and local history are fully restored.)
 */
object Recovery {

    private const val PREFS = "bitchat_recovery"
    private const val KEY_SEED = "seed"
    private const val KEY_INTRO_DONE = "intro_done"
    private const val ENC_PREFIX = "enc1:"

    /** 32 chars, excludes 0/O/1/I so the key is typeable without ambiguity. */
    private const val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    private const val SEED_BYTES = 18
    private const val DATA_CHARS = 29
    private const val KEY_CHARS = DATA_CHARS + 1 // + checksum

    private val random = SecureRandom()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------------
    // Seed / prefs
    // ------------------------------------------------------------------

    fun getSeed(context: Context): ByteArray? {
        val stored = (prefs ?: run { init(context); prefs })
            ?.getString(KEY_SEED, null) ?: return null
        return try {
            if (stored.startsWith(ENC_PREFIX)) {
                KeystoreVault.decrypt(Base64.decode(stored.removePrefix(ENC_PREFIX), Base64.NO_WRAP))
            } else {
                Base64.decode(stored, Base64.NO_WRAP)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun storeSeed(context: Context, seed: ByteArray) {
        val encoded = ENC_PREFIX + Base64.encodeToString(KeystoreVault.encrypt(seed), Base64.NO_WRAP)
        (prefs ?: run { init(context); prefs })
            ?.edit()?.putString(KEY_SEED, encoded)?.apply()
    }

    /** First launch: generate the seed that becomes the recovery key. */
    internal fun createSeed(context: Context): ByteArray {
        val seed = ByteArray(SEED_BYTES).also { random.nextBytes(it) }
        storeSeed(context, seed)
        return seed
    }

    fun introDone(context: Context): Boolean =
        (prefs ?: run { init(context); prefs })?.getBoolean(KEY_INTRO_DONE, false) ?: false

    fun confirmIntro(context: Context) {
        (prefs ?: run { init(context); prefs })
            ?.edit()?.putBoolean(KEY_INTRO_DONE, true)?.apply()
    }

    // ------------------------------------------------------------------
    // Key codec (pure — unit tested)
    // ------------------------------------------------------------------

    /** 18 bytes -> 29 alphabet chars (no padding: 144 of 145 bits used). */
    fun encode(seed: ByteArray): String {
        require(seed.size == SEED_BYTES) { "seed must be $SEED_BYTES bytes" }
        val out = StringBuilder(DATA_CHARS)
        var acc = 0
        var bits = 0
        for (b in seed) {
            acc = (acc shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out.append(ALPHABET[(acc shr bits) and 0x1F])
            }
        }
        if (bits > 0) out.append(ALPHABET[(acc shl (5 - bits)) and 0x1F])
        check(out.length == DATA_CHARS)
        return out.toString()
    }

    /** Checksum char appended: sum of char indices mod 32. */
    fun withChecksum(data: String): String {
        var sum = 0
        for (c in data) sum += ALPHABET.indexOf(c)
        return data + ALPHABET[sum and 0x1F]
    }

    private fun checksumOk(chars: String): Boolean {
        if (chars.length != KEY_CHARS) return false
        var sum = 0
        for (i in 0 until DATA_CHARS) {
            val idx = ALPHABET.indexOf(chars[i])
            if (idx < 0) return false
            sum += idx
        }
        return chars[DATA_CHARS] == ALPHABET[sum and 0x1F]
    }

    /** Grouped display form: XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-XXXXX. */
    fun format(key: String): String =
        key.chunked(5).joinToString("-")

    /**
     * Tolerant parse: strips dashes/spaces, upcases, verifies length and
     * checksum. Returns the full 30-char key or null on any deviation.
     */
    fun parseOrNull(userInput: String): String? {
        val clean = userInput.uppercase().filter { it in ALPHABET }
        if (clean.length != KEY_CHARS || !checksumOk(clean)) return null
        return clean
    }

    /** 29 data chars -> 18-byte seed. */
    fun seedFromKey(key: String): ByteArray {
        val out = ByteArray(SEED_BYTES)
        var acc = 0
        var bits = 0
        var i = 0
        for (c in key.take(DATA_CHARS)) {
            acc = (acc shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                bits -= 8
                out[i++] = (acc shr bits).toByte()
            }
        }
        check(i == SEED_BYTES)
        return out
    }

    /** nodeId = first 16 bytes of SHA-256(seed): deterministic mesh address. */
    fun deriveNodeId(seed: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(seed)
            .copyOfRange(0, 16)
            .joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------
    // Restore
    // ------------------------------------------------------------------

    /** Validates the key, stores the seed and re-derives the identity in place. */
    fun restoreFromKey(context: Context, userInput: String): Boolean {
        val key = parseOrNull(userInput) ?: return false
        val seed = seedFromKey(key)
        storeSeed(context, seed)
        CryptoEngine.initFromSeed(context, seed)
        com.bitchat.mesh.NodeIdentity.clearNodeId(context)
        confirmIntro(context)
        return true
    }
}