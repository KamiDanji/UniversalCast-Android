package com.universalcast.sender.app

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.universalcast.security.EcKeys
import com.universalcast.security.SenderIdentity
import com.universalcast.storage.Aead
import com.universalcast.storage.AesGcmAead
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * This phone's long-term identity: an EC P-256 key generated inside the Android Keystore. The private key is
 * non-exportable; the TV recognises the phone by the SHA-256 of the public key (the sender id).
 * Regenerated only if missing or unusable; that changes the sender id and voids all pairings, which the
 * runtime detects and handles.
 */
class KeystoreSenderIdentity private constructor(
    private val key: PrivateKey,
    override val publicKeySpki: ByteArray,
    val regenerated: Boolean,
) : SenderIdentity {
    override fun sign(data: ByteArray): ByteArray = EcKeys.sign(key, data)

    companion object {
        private const val PROVIDER = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "ucp_sender_identity_v1"

        @Synchronized
        fun loadOrCreate(alias: String = DEFAULT_ALIAS): KeystoreSenderIdentity {
            val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
            existing(ks, alias)?.let { return it }
            runCatching { ks.deleteEntry(alias) }
            val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
            gen.initialize(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
            gen.generateKeyPair()
            val created = existing(ks, alias) ?: error("Freshly generated phone key is unusable")
            return KeystoreSenderIdentity(created.key, created.publicKeySpki, regenerated = true)
        }

        private fun existing(ks: KeyStore, alias: String): KeystoreSenderIdentity? = try {
            val key = ks.getKey(alias, null) as? PrivateKey
            val spki = ks.getCertificate(alias)?.publicKey?.encoded
            if (key == null || spki == null) {
                null
            } else {
                EcKeys.decodePublicKey(spki) // rejects anything that is not a valid P-256 key
                KeystoreSenderIdentity(key, spki, regenerated = false)
            }
        } catch (e: Exception) {
            null
        }
    }
}

/** AES-256-GCM key in the Keystore protecting the persisted list of paired TVs. */
object KeystoreStoreKey {
    private const val PROVIDER = "AndroidKeyStore"
    const val DEFAULT_ALIAS = "ucp_sender_store_key_v1"

    fun aead(alias: String = DEFAULT_ALIAS): Aead = AesGcmAead { key(alias) }

    @Synchronized
    private fun key(alias: String): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // AesGcmAead supplies its own random 96-bit IV
                .build()
        )
        return gen.generateKey()
    }
}
