package com.universalcast.core

import com.universalcast.auth.Authenticator
import com.universalcast.config.ConfigValidator
import com.universalcast.config.NetworkConfig
import com.universalcast.config.ReceiverTimeouts
import com.universalcast.discovery.AdvertisementController
import com.universalcast.discovery.DiscoveryRecords
import com.universalcast.discovery.ServiceAdvertiser
import com.universalcast.identity.SenderRegistry
import com.universalcast.network.ConnectedSender
import com.universalcast.network.ConnectionsListener
import com.universalcast.network.NetworkAddresses
import com.universalcast.network.UcpProtocolHandler
import com.universalcast.network.UcpServer
import com.universalcast.pairing.PairingBootstrap
import com.universalcast.pairing.PairingManager
import com.universalcast.playback.PlaybackEngine
import com.universalcast.security.ReceiverIdentity
import com.universalcast.security.SystemTimeSource
import com.universalcast.security.TimeSource
import com.universalcast.session.SessionEndReason
import com.universalcast.session.SessionManager
import com.universalcast.ucp.Ucp
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Composition root of the receiver: identity + registry + playback engine + secure server + pairing +
 * discovery. It contains no Android and no Media3 types, so it is fully testable on the JVM.
 */
class ReceiverCore(
    val identity: ReceiverIdentity,
    val registry: SenderRegistry,
    val engine: PlaybackEngine,
    val config: NetworkConfig,
    advertiser: ServiceAdvertiser?,
    private val addressProvider: () -> List<String> = { NetworkAddresses.local() },
    private val receiverName: () -> String,
    private val time: TimeSource = SystemTimeSource,
    val timeouts: ReceiverTimeouts = ReceiverTimeouts(),
    val log: DiagnosticsLog = DiagnosticsLog(),
) {
    val sessions = SessionManager(time)
    val authenticator = Authenticator(identity.fingerprint, time, timeouts.challengeTtlMs)
    val pairing = PairingManager(
        identity, registry, time,
        addresses = addressProvider,
        port = { boundPort },
        receiverName = receiverName,
        windowMs = timeouts.pairingWindowMs,
    )
    val handler = UcpProtocolHandler(
        identity, registry, engine, sessions, pairing, authenticator, time, timeouts, log, receiverName
    )

    private val advertisement = advertiser?.let { AdvertisementController(it) }
    private var server: UcpServer? = null
    private var scheduler: ScheduledExecutorService? = null

    @Volatile
    var boundPort: Int = 0
        private set

    @get:Synchronized
    var isRunning: Boolean = false
        private set

    val name: String get() = receiverName()

    @Synchronized
    fun start() {
        if (isRunning) return
        ConfigValidator.requireValid(config)
        val ctx = identity.createServerSslContext()
        val srv = UcpServer(InetSocketAddress(config.port), ctx, handler, log)
        srv.startAndWait(5_000)
        server = srv
        boundPort = srv.port
        handler.attach()
        scheduler = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ucp-sweeper").also { it.isDaemon = true } }.also {
            it.scheduleWithFixedDelay({
                try {
                    handler.sweep()
                    pairing.tick()
                } catch (e: Exception) {
                    log.error("Sweeper failure", e)
                }
            }, 500, 500, TimeUnit.MILLISECONDS)
        }
        isRunning = true
        log.info("Secure receiver listening on port $boundPort (${config.mode.wire}), fingerprint ${identity.fingerprint.take(16)}")
        advertise()
    }

    /** (Re-)publishes the mDNS record. Safe to call repeatedly; never produces duplicates. */
    @Synchronized
    fun advertise() {
        val ctl = advertisement ?: return
        if (!isRunning) return
        try {
            ctl.ensure(DiscoveryRecords.forReceiver(identity, config, receiverName(), boundPort))
        } catch (e: Exception) {
            log.error("Service advertisement failed", e)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning) return
        isRunning = false
        try {
            advertisement?.stop()
        } catch (e: Exception) {
            log.warn("Advertisement stop failed")
        }
        scheduler?.shutdownNow()
        scheduler = null
        pairing.reject()
        sessions.endAll(SessionEndReason.SHUTDOWN)
        handler.closeAll(Ucp.CLOSE_SHUTDOWN, "receiver stopping")
        handler.detach()
        server?.stopQuietly()
        server = null
        boundPort = 0
        log.info("Receiver stopped")
    }

    fun startPairing(): PairingBootstrap = pairing.startPairing()

    fun revokeSender(senderId: String): Boolean = handler.revokeSender(senderId)

    fun connectedSenders(): List<ConnectedSender> = handler.connectedSenders()

    fun addConnectionsListener(l: ConnectionsListener) = handler.addConnectionsListener(l)
}
