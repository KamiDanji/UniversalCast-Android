package com.universalcast.discovery

import com.universalcast.config.NetworkConfig
import com.universalcast.security.ReceiverIdentity
import com.universalcast.ucp.Ucp

data class ServiceAdvertisement(
    val serviceName: String,
    val serviceType: String,
    val port: Int,
    val txt: Map<String, String>,
)

/** Platform mDNS registration (Android NSD in the app). Implementations must be idempotent per start/stop pair. */
interface ServiceAdvertiser {
    val isAdvertising: Boolean
    fun start(ad: ServiceAdvertisement)
    fun stop()
}

object DiscoveryRecords {
    /**
     * `_universalcast._tcp` with TXT: ucp (protocol version), id (device id), path (control path),
     * tls (always 1: only the secure endpoint is ever advertised) and mode (production|development).
     */
    fun forReceiver(identity: ReceiverIdentity, config: NetworkConfig, name: String, port: Int): ServiceAdvertisement {
        check(config.tlsEnabled) { "Refusing to advertise a non-TLS endpoint" }
        require(port in 1..65535) { "Invalid port" }
        val cleaned = name.filter { !it.isISOControl() }.trim().ifEmpty { "UniversalCast Receiver" }
        val serviceName = truncateUtf8(cleaned, 63)
        val txt = linkedMapOf(
            "ucp" to Ucp.VERSION.toString(),
            "id" to identity.deviceId,
            "path" to Ucp.WS_PATH,
            "tls" to "1",
            "mode" to config.mode.wire,
        )
        txt.forEach { (k, v) -> require(k.length + v.length + 1 <= 255) { "TXT entry too long" } }
        return ServiceAdvertisement(serviceName, Ucp.SERVICE_TYPE, port, txt)
    }

    private fun truncateUtf8(s: String, maxBytes: Int): String {
        var out = s
        while (out.toByteArray(Charsets.UTF_8).size > maxBytes) out = out.dropLast(1)
        return out
    }
}

/** Guarantees at most one live advertisement; repeated identical requests are no-ops. */
class AdvertisementController(private val advertiser: ServiceAdvertiser) {
    private var current: ServiceAdvertisement? = null

    @Synchronized
    fun ensure(ad: ServiceAdvertisement) {
        if (current == ad && advertiser.isAdvertising) return
        if (advertiser.isAdvertising) advertiser.stop()
        advertiser.start(ad)
        current = ad
    }

    @Synchronized
    fun stop() {
        if (advertiser.isAdvertising) advertiser.stop()
        current = null
    }
}
