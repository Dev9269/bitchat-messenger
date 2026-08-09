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
    private const val ENC_PREFIX = "enc1:"

    private val random = SecureRandom()

    private var xPriv: X25519PrivateKeyParameters? = null
    private var xPub: ByteArray = ByteArray(0)
    private var edPriv: Ed25519PrivateKeyParameters? = null
    private var edPub: ByteArray = ByteArray(0)

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(KEY_X_PRIV, null)
        val decoded = existing?.let { decodeStored(it) }
        if (decoded != null) {
            xPriv = X25519PrivateKeyParameters(decoded, 0)
            xPub = decodeStored(prefs.getString(KEY_X_PUB, null)!!) ?: return
            edPriv = decodeStored(prefs.getString(KEY_E_PRIV, null)!!)?.let { Ed25519PrivateKeyParameters(it, 0) } ?: return
            edPub = decodeStored(prefs.getString(KEY_E_PUB, null)!!) ?: return
            if (!existing!!.startsWith(ENC_PREFIX)) {
                writeEncrypted(prefs)
            }
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

            writeEncrypted(prefs)
        }
    }

    private fun writeEncrypted(prefs: android.content.SharedPreferences) {
        prefs.edit()
            .putString(KEY_X_PRIV, encodeStored(xPriv!!.encoded))
            .putString(KEY_X_PUB, encodeStored(xPub))
            .putString(KEY_E_PRIV, encodeStored(edPriv!!.encoded))
            .putString(KEY_E_PUB, encodeStored(edPub))
            .apply()
    }

    private fun encodeStored(blob: ByteArray): String =
        ENC_PREFIX + Base64.encodeToString(KeystoreVault.encrypt(blob), Base64.NO_WRAP)

    private fun decodeStored(value: String): ByteArray? =
        if (value.startsWith(ENC_PREFIX)) {
            KeystoreVault.decrypt(Base64.decode(value.removePrefix(ENC_PREFIX), Base64.NO_WRAP))
        } else {
            Base64.decode(value, Base64.NO_WRAP)
        }

    fun x25519PublicKey(): ByteArray = xPub

    private fun deriveKey(material: ByteArray, salt: ByteArray): ByteArray {
        val hkdf = HKDFBytesGenerator(SHA256Digest())
        hkdf.init(HKDFParameters(material, null, salt))
        val key = ByteArray(32)
        hkdf.generateBytes(key, 0, key.size)
        return key
    }

    private fun messageKey(peerPub: ByteArray, msgId: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(xPriv)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), shared, 0)
        return deriveKey(shared, "bitchat-dm".toByteArray(Charsets.UTF_8) + msgId)
    }

    private fun sharedSecret(peerPub: ByteArray): ByteArray {
        val agreement = X25519Agreement()
        agreement.init(xPriv)
        val shared = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(peerPub, 0), shared, 0)
        return shared
    }

    fun encryptDM(peerPub: ByteArray, msgId: ByteArray, plaintext: ByteArray): ByteArray {
        val key = messageKey(peerPub, msgId)
        return chachaEncrypt(key, msgId, plaintext)
    }

    fun decryptDM(peerPub: ByteArray, msgId: ByteArray, envelope: ByteArray): ByteArray? {
        if (envelope.size < 28) return null
        return try {
            val key = messageKey(peerPub, msgId)
            chachaDecrypt(key, msgId, envelope)
        } catch (_: Exception) {
            null
        }
    }

    fun newGroupKey(): ByteArray = ByteArray(32).also { random.nextBytes(it) }

    fun wrapGroupKey(memberPub: ByteArray, groupId: String, groupKey: ByteArray): ByteArray {
        val key = deriveKey(sharedSecret(memberPub), "bitchat-gk".toByteArray(Charsets.UTF_8) + groupId.toByteArray(Charsets.UTF_8))
        val ad = groupKeySalt(groupId)
        return chachaEncrypt(key, ad, groupKey)
    }

    fun unwrapGroupKey(memberPub: ByteArray, groupId: String, envelope: ByteArray): ByteArray? {
        if (envelope.size < 28) return null
        return try {
            val key = deriveKey(sharedSecret(memberPub), "bitchat-gk".toByteArray(Charsets.UTF_8) + groupId.toByteArray(Charsets.UTF_8))
            chachaDecrypt(key, groupKeySalt(groupId), envelope)
        } catch (_: Exception) {
            null
        }
    }

    fun encryptGroupMessage(groupKey: ByteArray, msgId: ByteArray, plaintext: ByteArray): ByteArray {
        val key = deriveKey(groupKey, "bitchat-gm".toByteArray(Charsets.UTF_8) + msgId)
        return chachaEncrypt(key, msgId, plaintext)
    }

    fun decryptGroupMessage(groupKey: ByteArray, msgId: ByteArray, envelope: ByteArray): ByteArray? {
        if (envelope.size < 28) return null
        return try {
            val key = deriveKey(groupKey, "bitchat-gm".toByteArray(Charsets.UTF_8) + msgId)
            chachaDecrypt(key, msgId, envelope)
        } catch (_: Exception) {
            null
        }
    }

    private fun groupKeySalt(groupId: String): ByteArray =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest("bitchat-gk:$groupId".toByteArray(Charsets.UTF_8))
            .copyOfRange(0, 16)

    private fun chachaEncrypt(key: ByteArray, ad: ByteArray, plaintext: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { random.nextBytes(it) }
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), HMAC_SIZE, nonce, ad))
        val out = ByteArray(12 + cipher.getOutputSize(plaintext.size))
        nonce.copyInto(out, 0)
        val len = cipher.processBytes(plaintext, 0, plaintext.size, out, 12)
        cipher.doFinal(out, 12 + len)
        return out
    }

    private fun chachaDecrypt(key: ByteArray, ad: ByteArray, envelope: ByteArray): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), HMAC_SIZE, envelope.copyOfRange(0, 12), ad))
        val ct = envelope.copyOfRange(12, envelope.size)
        val out = ByteArray(cipher.getOutputSize(ct.size))
        val len = cipher.processBytes(ct, 0, ct.size, out, 0)
        cipher.doFinal(out, len)
        return out
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
