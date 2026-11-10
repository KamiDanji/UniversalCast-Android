package com.universalcast

import com.universalcast.config.NetworkConfig
import com.universalcast.config.OperatingMode
import com.universalcast.config.ReceiverTimeouts
import com.universalcast.core.ReceiverCore
import com.universalcast.discovery.ServiceAdvertisement
import com.universalcast.discovery.ServiceAdvertiser
import com.universalcast.identity.InMemorySenderRegistry
import com.universalcast.identity.Role
import com.universalcast.network.Connection
import com.universalcast.playback.MediaItemInfo
import com.universalcast.playback.PlaybackEngine
import com.universalcast.playback.PlaybackListener
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.security.EcKeys
import com.universalcast.security.KeyBackedReceiverIdentity
import com.universalcast.security.SenderIdentity
import com.universalcast.security.SoftwareSenderIdentity
import com.universalcast.security.SystemTimeSource
import com.universalcast.security.TimeSource
import com.universalcast.sender.PairingClient
import com.universalcast.sender.PairingResult
import com.universalcast.sender.UcpClient
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.util.Date
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.fail

object TestPki {
    fun receiverIdentity(): KeyBackedReceiverIdentity {
        val kp = EcKeys.generateKeyPair()
        val name = X500Name("CN=UniversalCast Test Receiver")
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), Date(now - 60_000), Date(now + 10L * 365 * 86_400_000), name, kp.public
        )
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(JcaContentSignerBuilder("SHA256withECDSA").build(kp.private)))
        return KeyBackedReceiverIdentity(kp.private, arrayOf(cert))
    }
}

class FakeTimeSource(var now: Long = 1_000_000L) : TimeSource {
    override fun nowMs(): Long = now
    fun advance(ms: Long) {
        now += ms
    }
}

class FakeConnection(override val id: String = UUID.randomUUID().toString()) : Connection {
    override val remoteAddress: String = "test-peer"
    val sent = CopyOnWriteArrayList<String>()
    @Volatile var closeCode: Int? = null
    override fun send(text: String) {
        sent.add(text)
    }

    override fun close(code: Int, reason: String) {
        closeCode = code
    }
}

class FakeAdvertiser : ServiceAdvertiser {
    var starts = 0
    var stops = 0
    var last: ServiceAdvertisement? = null
    @Volatile override var isAdvertising = false
    override fun start(ad: ServiceAdvertisement) {
        starts++
        last = ad
        isAdvertising = true
    }

    override fun stop() {
        stops++
        isAdvertising = false
    }
}

/** In-memory playback engine that records every call and publishes snapshots like a real one. */
class FakePlaybackEngine : PlaybackEngine {
    private var snap = PlaybackSnapshot()
    val calls = CopyOnWriteArrayList<String>()
    private val listeners = CopyOnWriteArrayList<PlaybackListener>()
    override val snapshot: PlaybackSnapshot @Synchronized get() = snap

    private fun update(f: (PlaybackSnapshot) -> PlaybackSnapshot) {
        val s = synchronized(this) {
            snap = f(snap).copy(revision = snap.revision + 1, capturedAtMs = System.currentTimeMillis())
            snap
        }
        listeners.forEach { it.onSnapshot(s) }
    }

    @Volatile var lastItem: MediaItemInfo? = null

    override fun load(item: MediaItemInfo, autoplay: Boolean, startPositionMs: Long) {
        lastItem = item
        calls.add("load:${item.uri}:$autoplay:$startPositionMs")
        update {
            it.copy(
                status = if (autoplay) PlaybackStatus.PLAYING else PlaybackStatus.READY, mediaUri = item.uri, title = item.title,
                positionMs = startPositionMs, durationMs = 60_000, isPlaying = autoplay, error = null
            )
        }
    }

    override fun play() {
        calls.add("play"); update { it.copy(status = PlaybackStatus.PLAYING, isPlaying = true) }
    }

    override fun pause() {
        calls.add("pause"); update { it.copy(status = PlaybackStatus.PAUSED, isPlaying = false) }
    }

    override fun resume() {
        calls.add("resume"); update { it.copy(status = PlaybackStatus.PLAYING, isPlaying = true) }
    }

    override fun stop() {
        calls.add("stop")
        update { PlaybackSnapshot(revision = it.revision, speed = it.speed, volume = it.volume) }
    }

    override fun seekTo(positionMs: Long) {
        calls.add("seek:$positionMs"); update { it.copy(positionMs = positionMs) }
    }

    override fun setSpeed(speed: Float) {
        calls.add("speed:$speed"); update { it.copy(speed = speed) }
    }

    override fun setVolume(volume: Float) {
        calls.add("volume:$volume"); update { it.copy(volume = volume) }
    }

    override fun addListener(listener: PlaybackListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: PlaybackListener) {
        listeners.remove(listener)
    }

    override fun release() {}
}

fun waitFor(timeoutMs: Long = 5_000, what: String = "condition", cond: () -> Boolean) {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        if (cond()) return
        Thread.sleep(20)
    }
    fail("Timed out waiting for $what")
}

/** A fully wired receiver listening on a real TLS socket (loopback, ephemeral port) for integration tests. */
class Env(timeouts: ReceiverTimeouts = ReceiverTimeouts(), time: TimeSource = SystemTimeSource) {
    val identity = TestPki.receiverIdentity()
    val registry = InMemorySenderRegistry()
    val engine = FakePlaybackEngine()
    val advertiser = FakeAdvertiser()
    val core = ReceiverCore(
        identity, registry, engine, NetworkConfig(mode = OperatingMode.DEVELOPMENT, port = 0), advertiser,
        addressProvider = { listOf("127.0.0.1") }, receiverName = { "Test TV" }, time = time, timeouts = timeouts
    )
    private val clients = ArrayList<UcpClient>()

    init {
        core.start()
    }

    val port: Int get() = core.boundPort

    fun client(sender: SenderIdentity, fingerprint: String = identity.fingerprint): UcpClient =
        UcpClient(sender, "127.0.0.1", port, fingerprint).also { clients.add(it) }

    /** Runs the whole pairing ceremony with a cooperative user. */
    fun pair(sender: SenderIdentity, role: Role, name: String = "Test Phone"): PairingResult {
        val bootstrap = core.startPairing()
        val pc = PairingClient(sender, bootstrap, name)
        val sas = pc.begin()
        kotlin.test.assertEquals(core.pairing.view()!!.sas, sas, "both sides must derive the same SAS")
        pc.confirm()
        kotlin.test.assertTrue(core.pairing.userConfirmSas(true))
        kotlin.test.assertTrue(core.pairing.approve(role))
        val result = pc.awaitResult() ?: fail("no pairing result")
        pc.client.close()
        return result
    }

    fun login(sender: SenderIdentity): UcpClient {
        val c = client(sender)
        kotlin.test.assertTrue(c.connect(), "TLS connect")
        c.authenticate()
        return c
    }

    fun pairedLogin(role: Role): Pair<SoftwareSenderIdentity, UcpClient> {
        val id = SoftwareSenderIdentity.generate()
        pair(id, role)
        return id to login(id)
    }

    fun close() {
        clients.forEach { it.close() }
        core.stop()
    }
}
