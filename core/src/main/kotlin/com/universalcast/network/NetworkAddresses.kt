package com.universalcast.network

import java.net.Inet6Address
import java.net.NetworkInterface

object NetworkAddresses {
    const val MAX_ADDRESSES = 8

    /** Usable local IPv4/IPv6 addresses (no loopback, no link-local, interface up), LAN IPv4 first, bounded. */
    fun local(): List<String> {
        val lanV4 = ArrayList<String>()
        val otherV4 = ArrayList<String>()
        val ulaV6 = ArrayList<String>()
        val globalV6 = ArrayList<String>()
        val otherV6 = ArrayList<String>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (nif in ifaces) {
                if (!nif.isUp || nif.isLoopback) continue
                val isLanIface = !nif.isPointToPoint && !nif.isVirtual
                for (addr in nif.inetAddresses) {
                    if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isAnyLocalAddress) continue
                    val text = addr.hostAddress?.substringBefore('%') ?: continue
                    if (addr is Inet6Address) {
                        val firstByte = addr.address.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        val isUla = (firstByte and 0xFE) == 0xFC || addr.isSiteLocalAddress
                        when {
                            isLanIface && isUla -> ulaV6.add(text)
                            isLanIface -> globalV6.add(text)
                            else -> otherV6.add(text)
                        }
                    } else {
                        if (isLanIface && addr.isSiteLocalAddress) lanV4.add(text) else otherV4.add(text)
                    }
                }
            }
        } catch (e: Exception) {
            // Interface enumeration can fail transiently while the network changes; report what we have.
        }
        return (lanV4 + otherV4 + ulaV6 + globalV6 + otherV6).distinct().take(MAX_ADDRESSES)
    }
}
