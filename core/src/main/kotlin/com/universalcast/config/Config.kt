package com.universalcast.config

import com.universalcast.ucp.Ucp

enum class OperatingMode(val wire: String) { PRODUCTION("production"), DEVELOPMENT("development") }

/**
 * Network posture of the receiver. There is deliberately NO unauthenticated or plaintext endpoint in any mode:
 * TLS, authentication and paired identities are mandatory everywhere, and [ConfigValidator] refuses to start
 * a receiver that would weaken that. DEVELOPMENT only changes the advertised mode and allows an ephemeral port.
 */
data class NetworkConfig(
    val mode: OperatingMode = OperatingMode.PRODUCTION,
    val port: Int = 8765, // not 8443: Chromecast built-in (mediashell) holds that on Android TV
    val tlsEnabled: Boolean = true,
    val requireAuthentication: Boolean = true,
    val requirePairedSenders: Boolean = true,
)

object ConfigValidator {
    fun problems(cfg: NetworkConfig): List<String> {
        val out = ArrayList<String>()
        if (!cfg.tlsEnabled) out.add("TLS (WSS) is mandatory; there is no plaintext fallback")
        if (!cfg.requireAuthentication) out.add("Authentication is mandatory")
        if (!cfg.requirePairedSenders) out.add("Paired sender identities are mandatory")
        val minPort = if (cfg.mode == OperatingMode.DEVELOPMENT) 0 else 1
        if (cfg.port < minPort || cfg.port > 65535) out.add("Port ${cfg.port} is out of range")
        return out
    }

    fun requireValid(cfg: NetworkConfig) {
        val p = problems(cfg)
        if (p.isNotEmpty()) throw IllegalStateException("Invalid receiver configuration: " + p.joinToString("; "))
    }
}

data class ReceiverTimeouts(
    val authTimeoutMs: Long = Ucp.AUTH_TIMEOUT_MS,
    val challengeTtlMs: Long = Ucp.CHALLENGE_TTL_MS,
    val pairingWindowMs: Long = Ucp.PAIRING_WINDOW_MS,
)
