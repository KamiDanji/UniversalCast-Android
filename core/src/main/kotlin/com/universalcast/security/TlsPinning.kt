package com.universalcast.security

import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocketFactory
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

class PinnedTls(val sslContext: SSLContext, val trustManager: PinnedTrustManager) {
    /**
     * Returns an [SSLSocketFactory] that applies [soTimeoutMs] before the TLS handshake begins, so a host that
     * accepts the TCP connection (or a half-set-up `adb reverse` tunnel) but never completes the TLS/WebSocket
     * handshake cannot block `connectBlocking` forever.
     */
    fun socketFactoryWithTimeout(soTimeoutMs: Int): SocketFactory {
        val delegate = sslContext.socketFactory
        return object : SSLSocketFactory() {
            private fun Socket.withTimeout(): Socket = apply { if (soTimeoutMs > 0 && soTimeout == 0) soTimeout = soTimeoutMs }
            override fun getDefaultCipherSuites(): Array<String> = delegate.defaultCipherSuites
            override fun getSupportedCipherSuites(): Array<String> = delegate.supportedCipherSuites
            override fun createSocket(): Socket = delegate.createSocket().withTimeout()
            override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket =
                delegate.createSocket(s, host, port, autoClose).withTimeout()
            override fun createSocket(host: String?, port: Int): Socket = delegate.createSocket(host, port).withTimeout()
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).withTimeout()
            override fun createSocket(host: InetAddress?, port: Int): Socket = delegate.createSocket(host, port).withTimeout()
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(address, port, localAddress, localPort).withTimeout()
        }
    }
}

object TlsPinning {
    fun create(expectedFingerprint: String): PinnedTls {
        require(Fingerprint.isWellFormed(expectedFingerprint.lowercase())) { "Malformed fingerprint" }
        val tm = PinnedTrustManager(expectedFingerprint)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return PinnedTls(ctx, tm)
    }
}
