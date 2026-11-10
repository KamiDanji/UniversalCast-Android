package com.universalcast.sender.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet6Address

/** A TV announcing itself via mDNS. The TXT `id` and the addresses are unauthenticated hints; TLS pinning is the trust. */
data class DiscoveredTv(val serviceName: String, val deviceId: String?, val hosts: List<String>, val port: Int, val mode: String?)

/** Browses `_universalcast._tcp` and resolves each service (one at a time: NSD only allows one active resolve). */
class NsdDiscovery(context: Context) {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val main = Handler(Looper.getMainLooper())
    private val found = LinkedHashMap<String, DiscoveredTv>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var listener: NsdManager.DiscoveryListener? = null

    private val _tvs = MutableStateFlow<List<DiscoveredTv>>(emptyList())
    val tvs: StateFlow<List<DiscoveredTv>> = _tvs.asStateFlow()

    private fun publish() { _tvs.value = found.values.toList() }

    fun start() = main.post {
        if (listener != null) return@post
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { listener = null }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onServiceFound(info: NsdServiceInfo) {
                main.post { queue.addLast(info); resolveNext() }
            }
            override fun onServiceLost(info: NsdServiceInfo) {
                main.post { found.remove(info.serviceName); publish() }
            }
        }
        listener = l
        try {
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
        } catch (e: Exception) {
            listener = null
        }
    }

    fun stop() = main.post {
        val l = listener ?: return@post
        listener = null
        queue.clear()
        try {
            nsd.stopServiceDiscovery(l)
        } catch (e: Exception) {
        }
        found.clear()
        publish()
    }

    private fun resolveNext() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    main.post {
                        resolving = false
                        if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ queue.addLast(serviceInfo); resolveNext() }, 500) else resolveNext()
                    }
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        toTv(serviceInfo)?.let { found[serviceInfo.serviceName] = it; publish() }
                        resolveNext()
                    }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }

    @Suppress("DEPRECATION")
    private fun toTv(i: NsdServiceInfo): DiscoveredTv? {
        val addr = i.host ?: return null
        val ip = addr.hostAddress?.substringBefore('%') ?: return null
        // Link-local IPv6 addresses need an interface scope and are never advertised in pairing codes either.
        if (addr is Inet6Address && (addr.isLinkLocalAddress || addr.isLoopbackAddress)) return null
        val attrs = i.attributes
        fun txt(k: String) = attrs?.get(k)?.let { String(it, Charsets.UTF_8) }
        if (txt("tls") != "1") return null // only ever consider the secure endpoint
        return DiscoveredTv(i.serviceName, txt("id"), listOf(ip), i.port, txt("mode"))
    }

    companion object {
        const val SERVICE_TYPE = "_universalcast._tcp."
    }
}
