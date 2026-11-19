package com.universalcast.network

import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Discovers UniversalCast receivers on mobile Wi-Fi hotspots where Android's mDNS multicast is often dropped.
 *
 * All discovered IP addresses are strictly untrusted hints: [com.universalcast.sender.SenderController] connects
 * over TLS with SPKI SHA-256 certificate fingerprint pinning, so a host on the subnet is accepted if and only if
 * it proves possession of the paired receiver's private key.
 */
object HotspotPeerFinder {
    /**
     * Fast scan for hosts listening on [port] on the same `/24` IPv4 subnet as [seedAddresses] or the device's
     * own LAN/hotspot interface. Completes within ~600 ms.
     */
    fun findCandidates(port: Int, seedAddresses: List<String> = emptyList(), timeoutMs: Int = 220): List<String> {
        if (port !in 1..65535) return emptyList()
        val arpHosts = readArpTable()
        val prefixes = LinkedHashSet<String>()
        for (addr in (NetworkAddresses.local() + seedAddresses + arpHosts)) {
            val parts = addr.split('.')
            if (parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 } && parts[0] != "127") {
                prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
            }
        }
        if (prefixes.isEmpty() && arpHosts.isEmpty()) return emptyList()

        val targets = LinkedHashSet<String>()
        // 1. ARP neighbors first (most likely to include a tethered car head unit)
        targets.addAll(arpHosts)
        // 2. Common hotspot gateway and first DHCP client ranges (.1..60, .100..150, .200..254)
        val localIps = NetworkAddresses.local().toSet()
        for (prefix in prefixes.take(2)) {
            for (host in listOf(1, 2) + (10..60) + (100..140) + (200..254)) {
                val candidate = "$prefix.$host"
                if (candidate !in localIps) targets.add(candidate)
            }
        }
        if (targets.isEmpty()) return emptyList()

        val pool = Executors.newFixedThreadPool(24) { r ->
            Thread(r, "ucp-hotspot-scan").also { it.isDaemon = true }
        }
        return try {
            val tasks = targets.map { ip ->
                Callable {
                    try {
                        Socket().use { s ->
                            s.connect(InetSocketAddress(ip, port), timeoutMs)
                            ip
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
            }
            val futures = pool.invokeAll(tasks, 900, TimeUnit.MILLISECONDS)
            futures.mapNotNull { f ->
                if (f.isDone && !f.isCancelled) runCatching { f.get() }.getOrNull() else null
            }
        } catch (_: Exception) {
            emptyList()
        } finally {
            pool.shutdownNow()
        }
    }

    /** Reads IPv4 neighbors from `/proc/net/arp` when readable (e.g. Android hotspot interface). */
    fun readArpTable(): List<String> = runCatching {
        val f = File("/proc/net/arp")
        if (!f.canRead()) return emptyList()
        f.useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val ip = line.trim().split(Regex("\\s+")).firstOrNull()
                ip?.takeIf { it.count { c -> c == '.' } == 3 && !it.startsWith("127.") && it != "0.0.0.0" }
            }.distinct().toList()
        }
    }.getOrDefault(emptyList())
}
