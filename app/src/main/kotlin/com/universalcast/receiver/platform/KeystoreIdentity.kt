package com.universalcast.receiver.platform

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.universalcast.security.EcKeys
import com.universalcast.security.KeyBackedReceiverIdentity
import com.universalcast.security.ReceiverIdentity
import com.universalcast.storage.Aead
import com.universalcast.storage.AesGcmAead
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.security.auth.x500.X500Principal

/**
 * Receiver identity in the Android Keystore. The EC P-256 private key is generated inside the keystore and is
 * never exportable, so no private key material ever exists in app storage or memory dumps of the app process.
 * The self-signed certificate is created by the keystore itself; its SPKI fingerprint is the receiver identity
 * that senders pin (there is no CA and no hostname check).
 *
 * The key is only regenerated when it is missing or unusable (wrong algorithm/curve, expired or mismatching
 * certificate). Regeneration changes the fingerprint, which intentionally invalidates all old pairings.
 */
class KeystoreIdentityProvider(
    private val alias: String = IDENTITY_ALIAS,
    private val storeKeyAlias: String = STORE_KEY_ALIAS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    class Result(val identity: ReceiverIdentity, val regenerated: Boolean)

    private val ks: KeyStore by lazy { KeyStore.getInstance(PROVIDER).apply { load(null) } }

    @Synchronized
    fun loadOrCreate(): Result {
        existing()?.let { return Result(it, false) }
        runCatching { ks.deleteEntry(alias) }
        generate()
        val identity = existing() ?: error("Freshly generated receiver key is unusable")
        return Result(identity, true)
    }

    private fun existing(): ReceiverIdentity? = try {
        val key = ks.getKey(alias, null) as? PrivateKey
        val cert = ks.getCertificate(alias) as? X509Certificate
        if (key == null || cert == null) {
            null
        } else {
            cert.checkValidity(Date(now()))
            // validates curve + on-curve; rejects anything but a P-256 key
            EcKeys.decodePublicKey(cert.publicKey.encoded)
            check(cert.publicKey.algorithm == "EC")
            KeyBackedReceiverIdentity(key, arrayOf(cert))
        }
    } catch (e: Exception) {
        null
    }

    private fun generate() {
        val start = Date(now() - 24L * 3600_000)
        val end = Date(now() + 20L * 365 * 24 * 3600_000)
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            // TLS 1.2 ECDHE_ECDSA suites may use SHA-384/512; TLS 1.3 uses SHA-256 for P-256.
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512, KeyProperties.DIGEST_NONE)
            .setCertificateSubject(X500Principal("CN=UniversalCast Receiver"))
            .setCertificateSerialNumber(BigInteger.valueOf(now()))
            .setCertificateNotBefore(start)
            .setCertificateNotAfter(end)
            .build()
        val gen = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, PROVIDER)
        gen.initialize(spec)
        gen.generateKeyPair()
    }

    /** AES-256-GCM key (non-exportable, in the keystore) protecting the persisted sender registry. */
    fun storeAead(): Aead = AesGcmAead { storeKey() }

    @Synchronized
    private fun storeKey(): SecretKey {
        (ks.getKey(storeKeyAlias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(storeKeyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // we supply our own random 96-bit IV (see AesGcmAead)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val IDENTITY_ALIAS = "ucp_receiver_identity_v1"
        const val STORE_KEY_ALIAS = "ucp_registry_key_v1"
    }
}
