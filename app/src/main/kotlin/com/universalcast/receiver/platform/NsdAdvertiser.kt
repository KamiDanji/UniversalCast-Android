package com.universalcast.receiver.platform

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.universalcast.core.DiagnosticsLog
import com.universalcast.discovery.ServiceAdvertisement
import com.universalcast.discovery.ServiceAdvertiser

/** Publishes `_universalcast._tcp` via Android NSD (mDNS). Registration is asynchronous; state is tracked in callbacks. */
class NsdAdvertiser(private val nsd: NsdManager, private val log: DiagnosticsLog) : ServiceAdvertiser {
    private var listener: NsdManager.RegistrationListener? = null

    @Volatile
    private var registered = false

    @Volatile
    var registeredName: String? = null
        private set

    override val isAdvertising: Boolean get() = listener != null

    @Synchronized
    override fun start(ad: ServiceAdvertisement) {
        if (listener != null) stop()
        val info = NsdServiceInfo().apply {
            serviceName = ad.serviceName
            // NSD wants "_name._tcp" (no ".local."); the platform adds the domain.
            serviceType = ad.serviceType.removeSuffix(".").removeSuffix(".local").removeSuffix(".")
            port = ad.port
            ad.txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {
                registered = true
                registeredName = i.serviceName
                log.info("mDNS registered as '${i.serviceName}'")
            }

            override fun onRegistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                registered = false
                log.error("mDNS registration failed (code $errorCode)")
            }

            override fun onServiceUnregistered(i: NsdServiceInfo) {
                registered = false
                log.info("mDNS unregistered")
            }

            override fun onUnregistrationFailed(i: NsdServiceInfo, errorCode: Int) {
                log.warn("mDNS unregistration failed (code $errorCode)")
            }
        }
        listener = l
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l)
    }

    @Synchronized
    override fun stop() {
        val l = listener ?: return
        listener = null
        registered = false
        try {
            nsd.unregisterService(l)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
    }

    val isRegistered: Boolean get() = registered
}
