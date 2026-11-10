package com.universalcast.network

import java.net.Inet6Address
import java.net.NetworkInterface

object NetworkAddresses {
    /** Usable local IPv4/IPv6 addresses (no loopback, no link-local, interface up), IPv4 first. */
    fun local(): List<String> {
        val v4 = ArrayList<String>()
        val v6 = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                    val text = addr.hostAddress?.substringBefore('%') ?: continue
                    if (addr is Inet6Address) v6.add(text) else v4.add(text)
                }
            }
        } catch (e: Exception) {
            // Interface enumeration can fail transiently while the network changes; report what we have.
        }
        return (v4 + v6).distinct()
    }
}
