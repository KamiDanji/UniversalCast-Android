package com.universalcast.security

import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Trusts exactly one server key: the one whose SPKI SHA-256 equals the pinned receiver fingerprint
 * (obtained out-of-band from the pairing QR code). Certificate chains, names and validity dates are not
 * consulted: the pin is the sole root of trust, which also avoids failures on TVs with a wrong clock.
 *
 * Extending [X509ExtendedTrustManager] stops the platform from layering hostname verification on top; the
 * receiver is addressed by LAN IP, so identity is established solely by the pinned key.
 */
class PinnedTrustManager(expectedFingerprint: String) : X509ExtendedTrustManager() {
    private val expected = expectedFingerprint.lowercase().toByteArray(Charsets.US_ASCII)

    @Volatile
    var peerSpki: ByteArray? = null
        private set

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("Client certificates are not accepted")
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkClientTrusted(chain, authType)

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkClientTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) =
        checkServerTrusted(chain, authType)

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain == null || chain.isEmpty()) throw CertificateException("Empty certificate chain")
        val spki = chain[0].publicKey.encoded
        val actual = Fingerprint.ofSpki(spki).toByteArray(Charsets.US_ASCII)
        if (!Hashing.constantTimeEquals(actual, expected)) {
            throw CertificateException("Receiver certificate does not match the pinned fingerprint")
        }
        peerSpki = spki
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

class PinnedTls(val sslContext: SSLContext, val trustManager: PinnedTrustManager)

object TlsPinning {
    fun create(expectedFingerprint: String): PinnedTls {
        require(Fingerprint.isWellFormed(expectedFingerprint.lowercase())) { "Malformed fingerprint" }
        val tm = PinnedTrustManager(expectedFingerprint)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return PinnedTls(ctx, tm)
    }
}
