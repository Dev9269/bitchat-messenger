package com.bitchat.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps small blobs (private keys, DB passphrase) with AES-256-GCM using a key held
 * in the Android Keystore, so plaintext never touches the disk. Falls back to an
 * in-memory key in environments without the AndroidKeyStore provider (unit tests).
 */
object KeystoreVault {

    private const val ALIAS = "bitchat_vault_key"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    /**
     * True only where the platform keystore provider is genuinely absent, i.e. JVM unit
     * tests. Where it is present every failure is propagated: quietly re-keying with a
     * throwaway in-memory key writes ciphertext the next process cannot read, which is how
     * identities used to be silently replaced on every launch.
     */
    private val keystoreAbsent: Boolean by lazy {
        try {
            KeyStore.getInstance("AndroidKeyStore").load(null)
            false
        } catch (_: Exception) {
            true
        }
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, vaultKey())
        val ct = cipher.doFinal(plain)
        return cipher.iv + ct
    }

    fun decrypt(blob: ByteArray): ByteArray? {
        if (blob.size <= IV_SIZE) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                vaultKey(),
                GCMParameterSpec(TAG_BITS, blob.copyOfRange(0, IV_SIZE))
            )
            cipher.doFinal(blob, IV_SIZE, blob.size - IV_SIZE)
        } catch (_: Exception) {
            null
        }
    }

    private fun vaultKey(): SecretKey {
        if (keystoreAbsent) return fallbackKey
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        return existing ?: generateKeystoreKey()
    }

    private fun generateKeystoreKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private val fallbackKey: SecretKey by lazy {
        KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    }
}
