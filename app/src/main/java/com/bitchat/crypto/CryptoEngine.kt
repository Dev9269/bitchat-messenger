package com.bitchat.crypto

import android.content.Context
import android.util.Base64
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.generators.X25519KeyPairGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519KeyGenerationParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

object CryptoEngine {

    private const val PREFS = "bitchat_keys"
    private const val KEY_X_PRIV = "x25519_priv"
    private const val KEY_X_PUB = "x25519_pub"
    private const val KEY_E_PRIV = "ed25519_priv"
    private const val KEY_E_PUB = "ed25519_pub"
    private const val HMAC_SIZE = 128

    private val random = SecureRandom()

    private var xPriv: X25519PrivateKeyParameters? = null
    private var xPub: ByteArray = ByteArray(0)
    private var edPriv: Ed25519PrivateKeyParameters? = null
    private var edPub: ByteArray = ByteArray(0)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_X_PRIV, null)
        if (existing != null) {
            xPriv = X25519PrivateKeyParameters(Base64.decode(existing, Base64.NO_WRAP), 0)
            xPub = Base64.decode(prefs.getString(KEY_X_PUB, null), Base64.NO_WRAP)
            edPriv = Ed25519PrivateKeyParameters(Base64.decode(prefs.getString(KEY_E_PRIV, null), Base64.NO_WRAP), 0)
            edPub = Base64.decode(prefs.getString(KEY_E_PUB, null), Base64.NO_WRAP)
        } else {
            val xgen = X25519KeyPairGenerator()
            xgen.init(X25519KeyGenerationParameters(random))
            val xpair = xgen.generateKeyPair()
            xPriv = xpair.private as X25519PrivateKeyParameters
            xPub = (xpair.public as X25519PublicKeyParameters).encoded

            val egen = Ed25519KeyPairGenerator()
            egen.init(Ed25519KeyGenerationParameters(random))
            val epair = egen.generateKeyPair()
            edPriv = epair.private as Ed25519PrivateKeyParameters
            edPub = (epair.public as Ed25519PublicKeyParameters).encoded

            prefs.edit()
                .putString(KEY_X_PRIV, Base64.encodeToString(xPriv!!.encoded, Base64.NO_WRAP))
                .putString(KEY_X_PUB, Base64.encodeToString(xPub, Base64.NO_WRAP))
                .putString(KEY_E_PRIV, Base64.encodeToString(edPriv!!.encoded, Base64.NO_WRAP))
                .putString(KEY_E_PUB, Base64.encodeToString(edPub, Base64.NO_WRAP))
                .apply()
        }
    }

    fun x25519PublicKey(): ByteArray = xPub

    private fun messageKey(peerPub: ByteArray, msgId: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(xPriv)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), shared, 0)
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(shared, null, "bitchat-dm".toByteArray(Charsets.UTF_8) + msgId))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, key.size)
        return key
    }

    fun encryptDM(peerPub: ByteArray, msgId: ByteArray, plaintext: ByteArray): ByteArray {
        val key = messageKey(peerPub, msgId)
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), HMAC_SIZE, nonce, msgId))
        val out = ByteArray(12 + cipher.getOutputSize(plaintext.size))
        nonce.copyInto(out, 0)
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 12)
        cipher.doFinal(out, 12 + len)
        return out
    }

    fun decryptDM(peerPub: ByteArray, msgId: ByteArray, envelope: ByteArray): ByteArray? {
        if (envelope.size < 28) return null
        return try {
            val key = messageKey(peerPub, msgId)
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), HMAC_SIZE, envelope.copyOfRange(0, 12), msgId))
            val ct = envelope.copyOfRange(12, envelope.size)
            val out = ByteArray(cipher.getOutputSize(ct.size))
            val len = cipher.processBytes(ct, 0, ct.size, out, 0)
            cipher.doFinal(out, len)
            out
        } catch (_: Exception) {
            null
        }
    }

    fun signBroadcast(text: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, edPriv)
        signer.update(text, 0, text.size)
        val sig = signer.generateSignature()
        return ByteArray(64 + edPub.size + text.size).also { out ->
            sig.copyInto(out, 0)
            edPub.copyInto(out, 64)
            text.copyInto(out, 64 + edPub.size)
        }
    }

    fun verifyBroadcast(assembled: ByteArray): ByteArray? {
        if (assembled.size < 96) return null
        val sig = assembled.copyOfRange(0, 64)
        val pub = assembled.copyOfRange(64, 96)
        val text = assembled.copyOfRange(96, assembled.size)
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(pub, 0))
            verifier.update(text, 0, text.size)
            if (verifier.verifySignature(sig)) text else null
        } catch (_: Exception) {
            null
        }
    }
}
