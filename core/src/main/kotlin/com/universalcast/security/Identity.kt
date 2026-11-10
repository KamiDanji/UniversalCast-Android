package com.universalcast.security

import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/** The receiver's long-term identity: one P-256 key that signs protocol proofs and backs the TLS certificate. */
interface ReceiverIdentity {
    /** DER SubjectPublicKeyInfo of the identity key. */
    val publicKeySpki: ByteArray
    val fingerprint: String get() = Fingerprint.ofSpki(publicKeySpki)
    val deviceId: String get() = fingerprint.substring(0, 16)
    fun sign(data: ByteArray): ByteArray
    fun createServerSslContext(): SSLContext
}

/**
 * [ReceiverIdentity] over any [PrivateKey] plus the X.509 chain whose leaf carries the matching public key.
 * On Android the private key is a non-exportable Android Keystore key; on the JVM (tests) it is a software key.
 */
class KeyBackedReceiverIdentity(
    private val privateKey: PrivateKey,
    val certificateChain: Array<X509Certificate>,
) : ReceiverIdentity {
    init {
        require(certificateChain.isNotEmpty()) { "Certificate chain is empty" }
        EcKeys.decodePublicKey(certificateChain[0].publicKey.encoded) // rejects anything but a valid P-256 key
    }

    override val publicKeySpki: ByteArray = certificateChain[0].publicKey.encoded

    override fun sign(data: ByteArray): ByteArray = EcKeys.sign(privateKey, data)

    override fun createServerSslContext(): SSLContext {
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(arrayOf(SingleKeyManager(privateKey, certificateChain)), null, SecureRandom())
        return ctx
    }
}

/**
 * Presents exactly one EC key/certificate for server authentication. Extends [X509ExtendedKeyManager] so the
 * SSLEngine used by the WebSocket server asks for the engine-specific alias directly.
 */
class SingleKeyManager(
    private val privateKey: PrivateKey,
    private val chain: Array<X509Certificate>,
) : X509ExtendedKeyManager() {
    private val alias = "ucp-receiver"

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (keyType == "EC") arrayOf(alias) else null

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        if (keyType == "EC") alias else null

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        if (keyType == "EC") alias else null

    override fun getCertificateChain(requestedAlias: String?): Array<X509Certificate>? =
        if (requestedAlias == alias) chain else null

    override fun getPrivateKey(requestedAlias: String?): PrivateKey? =
        if (requestedAlias == alias) privateKey else null
}

/** A sender device's long-term identity. Secure storage of the private key is the sender application's job. */
interface SenderIdentity {
    val publicKeySpki: ByteArray
    val senderId: String get() = Fingerprint.ofSpki(publicKeySpki)
    fun sign(data: ByteArray): ByteArray
}

/** Software P-256 sender identity (used by tests and as a reference for sender implementations). */
class SoftwareSenderIdentity(private val keyPair: KeyPair) : SenderIdentity {
    override val publicKeySpki: ByteArray = keyPair.public.encoded

    override fun sign(data: ByteArray): ByteArray = EcKeys.sign(keyPair.private, data)

    /** PKCS#8 export so the sender app can persist the key inside its own secure storage. */
    fun exportPrivateKeyPkcs8(): ByteArray = keyPair.private.encoded

    companion object {
        fun generate(): SoftwareSenderIdentity = SoftwareSenderIdentity(EcKeys.generateKeyPair())

        fun restore(pkcs8: ByteArray, spki: ByteArray): SoftwareSenderIdentity {
            val kf = KeyFactory.getInstance("EC")
            val priv = kf.generatePrivate(PKCS8EncodedKeySpec(pkcs8))
            return SoftwareSenderIdentity(KeyPair(EcKeys.decodePublicKey(spki), priv))
        }
    }
}
