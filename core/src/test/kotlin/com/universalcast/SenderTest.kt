package com.universalcast

import com.universalcast.identity.Role
import com.universalcast.playback.PlaybackSnapshot
import com.universalcast.playback.PlaybackStatus
import com.universalcast.security.SoftwareSenderIdentity
import com.universalcast.sender.*
import com.universalcast.storage.AesGcmAead
import com.universalcast.storage.LoadOutcome
import com.universalcast.ucp.Ucp
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator
import kotlin.test.*

/** The phone-side logic (pairing, connection, commands, events, reconnect) against a real receiver over WSS. */
class SenderTest {
    private val url = "https://example.org/video.mp4"

    private inline fun withEnv(timeouts: com.universalcast.config.ReceiverTimeouts = com.universalcast.config.ReceiverTimeouts(), block: (Env) -> Unit) {
        val env = Env(timeouts)
        try { block(env) } finally { env.close() }
    }

    /** Pairs [identity] through the real PairingSession and returns the stored receiver record. */
    private fun pair(env: Env, identity: SoftwareSenderIdentity, role: Role): PairedReceiver {
        val ps = PairingSession(identity, "Test Phone", env.core.startPairing().toUri())
        val sas = ps.begin()
        assertEquals(env.core.pairing.view()!!.sas, sas)
        ps.confirm()
        assertTrue(env.core.pairing.userConfirmSas(true))
        assertTrue(env.core.pairing.approve(role))
        val out = ps.awaitApproval(10_000)
        assertIs<PairOutcome.Approved>(out)
        return out.receiver
    }

    private fun controller(id: SoftwareSenderIdentity, hb: Long = 10_000, delays: List<Long> = listOf(100L, 200L, 400L)) =
        SenderController(id, connectTimeoutMs = 2_000, heartbeatMs = hb, reconnectDelaysMs = delays)

    private fun SenderController.awaitPhase(p: ConnectionPhase, ms: Long = 6_000) = waitFor(ms, "phase $p (now ${state.phase}: ${state.message})") { state.phase == p }

    // ---- pairing --------------------------------------------------------------------------------------

    @Test fun pairingSessionProducesStoredReceiverWithVerifiedApproval() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate()
        val r = pair(env, id, Role.CONTROLLER)
        assertEquals(env.identity.fingerprint, r.fingerprint)
        assertEquals("Test TV", r.name)
        assertEquals("CONTROLLER", r.role)
        assertEquals(listOf("127.0.0.1"), r.addresses)
        assertEquals(env.port, r.port)
        assertNotNull(env.registry.find(id.senderId))
    }

    @Test fun observerRoleIsReportedToThePhone() = withEnv { env ->
        assertEquals("OBSERVER", pair(env, SoftwareSenderIdentity.generate(), Role.OBSERVER).role)
    }

    @Test fun rejectedPairingIsReportedAndNothingIsStored() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate()
        val ps = PairingSession(id, "Phone", env.core.startPairing().toUri())
        ps.ensureSasMatches(env)
        ps.confirm()
        env.core.pairing.userConfirmSas(true)
        env.core.pairing.reject()
        assertIs<PairOutcome.Rejected>(ps.awaitApproval(10_000))
        assertNull(env.registry.find(id.senderId))
    }

    private fun PairingSession.ensureSasMatches(env: Env) { val sas = begin(); assertEquals(env.core.pairing.view()!!.sas, sas) }

    @Test fun cancelledPairingFailsTheTvSide() = withEnv { env ->
        val ps = PairingSession(SoftwareSenderIdentity.generate(), "Phone", env.core.startPairing().toUri())
        ps.begin(); ps.cancel()
        waitFor(what = "pairing failure on TV") { env.core.pairing.view()?.isTerminal == true }
    }

    @Test fun garbageQrCodesAreRejectedBeforeAnyNetworkAccess() {
        val id = SoftwareSenderIdentity.generate()
        for (bad in listOf("", "hello", "https://example.org", "ucp://pair?d=", "ucp://pair?d=!!!", "ucp://pair?d=" + "A".repeat(5000))) {
            assertFailsWith<IllegalArgumentException>(bad.take(30)) { PairingSession(id, "P", bad) }
        }
    }

    @Test fun pairingWithAnExpiredWindowFails() = withEnv(com.universalcast.config.ReceiverTimeouts(pairingWindowMs = 300)) { env ->
        val qr = env.core.startPairing().toUri()
        Thread.sleep(600)
        assertFailsWith<UcpClientException> { PairingSession(SoftwareSenderIdentity.generate(), "P", qr).begin() }
    }

    // ---- connection and commands --------------------------------------------------------------------------

    @Test fun controllerConnectsAndControlsPlayback() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            var authed: PairedReceiver? = null
            c.onAuthenticated = { authed = it }
            c.connect(r)
            c.awaitPhase(ConnectionPhase.CONNECTED)
            assertEquals("CONTROLLER", c.state.role); assertTrue(c.state.canControl); assertEquals(r, authed)

            c.load(url, "My video")
            waitFor(what = "playing") { c.state.snapshot.status == PlaybackStatus.PLAYING }
            assertEquals(url, c.state.snapshot.mediaUri); assertTrue(c.state.hasSession)
            assertEquals("load:$url:true:0", env.engine.calls.last())

            c.pause(); waitFor { c.state.snapshot.status == PlaybackStatus.PAUSED }
            c.resume(); waitFor { c.state.snapshot.status == PlaybackStatus.PLAYING }
            c.seek(42_000); waitFor { c.state.snapshot.positionMs == 42_000L }
            c.setSpeed(1.5); waitFor { c.state.snapshot.speed == 1.5f }
            c.setVolume(0.25); waitFor { c.state.snapshot.volume == 0.25f }
            c.stop(); waitFor { c.state.snapshot.status == PlaybackStatus.IDLE }
            assertNull(c.state.message)
        } finally { c.shutdown() }
    }

    @Test fun receiverSideChangesArePushedToThePhone() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            c.load(url); waitFor { c.state.snapshot.status == PlaybackStatus.PLAYING }
            env.engine.pause() // e.g. the TV user pressed pause on the remote
            waitFor(what = "pause pushed") { c.state.snapshot.status == PlaybackStatus.PAUSED }
        } finally { c.shutdown() }
    }

    @Test fun phoneJoiningLaterSeesWhatIsAlreadyPlaying() = withEnv { env ->
        val (_, other) = env.pairedLogin(Role.CONTROLLER)
        val sid = com.universalcast.ucp.Fields(other.call(com.universalcast.ucp.Method.SESSION_CREATE).payload).string("sessionId")
        other.call(com.universalcast.ucp.Method.MEDIA_LOAD, mapOf("uri" to url), sid)
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.OBSERVER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            waitFor { c.state.snapshot.mediaUri == url }
            assertEquals(PlaybackStatus.PLAYING, c.state.snapshot.status)
        } finally { c.shutdown() }
    }

    @Test fun observerCannotControlAndTvIsUntouched() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.OBSERVER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            assertEquals("OBSERVER", c.state.role); assertFalse(c.state.canControl)
            c.load(url); c.pause()
            waitFor { c.state.message?.contains("observer") == true }
            assertTrue(env.engine.calls.isEmpty())
        } finally { c.shutdown() }
    }

    @Test fun invalidLinksNeverReachTheTv() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            for (bad in listOf("", "not a url", "file:///sdcard/a.mp4", "content://x/1", "ftp://h/a", "javascript:alert(1)")) {
                c.load(bad)
                waitFor(what = "message for '$bad'") { c.state.message?.contains("valid") == true }
            }
            assertTrue(env.engine.calls.isEmpty())
        } finally { c.shutdown() }
    }

    @Test fun commandsWithoutAConnectionExplainThemselves() {
        val c = controller(SoftwareSenderIdentity.generate())
        try {
            c.pause()
            waitFor { c.state.message == "Not connected to a TV." }
        } finally { c.shutdown() }
    }

    @Test fun anotherControllerTakingOverIsReportedAndCanBeTakenBack() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            c.load(url); waitFor { c.state.hasSession && c.state.snapshot.status == PlaybackStatus.PLAYING }
            val (_, other) = env.pairedLogin(Role.CONTROLLER)
            other.call(com.universalcast.ucp.Method.SESSION_CREATE)
            waitFor(what = "takeover notice") { !c.state.hasSession && c.state.message?.contains("took over") == true }
            c.pause() // explicit user action: silently re-creates a session and controls again
            waitFor { c.state.snapshot.status == PlaybackStatus.PAUSED && c.state.hasSession }
        } finally { c.shutdown() }
    }

    @Test fun heartbeatAndCommandsNeverCollideOnSequenceNumbers() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id, hb = 40)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            c.load(url)
            repeat(30) { c.seek(1_000L * it); Thread.sleep(15) }
            waitFor { c.state.snapshot.positionMs == 29_000L }
            assertEquals(ConnectionPhase.CONNECTED, c.state.phase)
            assertNull(c.state.message, "no SEQUENCE_ERROR or other fault may surface")
        } finally { c.shutdown() }
    }

    // ---- failures -----------------------------------------------------------------------------------------

    @Test fun revokedPhoneIsToldSoAndDoesNotReconnect() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            env.core.revokeSender(id.senderId)
            c.awaitPhase(ConnectionPhase.FAILED)
            assertEquals(FailureKind.REJECTED, c.state.failure)
            Thread.sleep(800)
            assertEquals(ConnectionPhase.FAILED, c.state.phase, "must not hammer a TV that revoked us")
            c.connect(r)
            c.awaitPhase(ConnectionPhase.FAILED); assertEquals(FailureKind.REJECTED, c.state.failure)
        } finally { c.shutdown() }
    }

    @Test fun unpairedPhoneIsRejected() = withEnv { env ->
        val ghost = PairedReceiver(env.identity.fingerprint, env.identity.deviceId, "Test TV", listOf("127.0.0.1"), env.port, Ucp.WS_PATH, "CONTROLLER", 1)
        val c = controller(SoftwareSenderIdentity.generate())
        try {
            c.connect(ghost); c.awaitPhase(ConnectionPhase.FAILED)
            assertEquals(FailureKind.REJECTED, c.state.failure)
        } finally { c.shutdown() }
    }

    @Test fun changedTvIdentityIsReportedAsPinMismatch() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            c.connect(r.copy(fingerprint = "ab".repeat(32))); c.awaitPhase(ConnectionPhase.FAILED)
            assertEquals(FailureKind.PIN_MISMATCH, c.state.failure)
        } finally { c.shutdown() }
    }

    @Test fun unreachableTvIsReportedAndStaleAddressesAreSkipped() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            // A stale first address (nothing listens on this port) must not prevent using the next hint.
            val badPort = java.net.ServerSocket(0).use { it.localPort }
            c.connect(r.copy(port = badPort)); c.awaitPhase(ConnectionPhase.FAILED)
            assertEquals(FailureKind.UNREACHABLE, c.state.failure)
            c.connect(r, preferredHosts = listOf("127.0.0.2")); c.awaitPhase(ConnectionPhase.CONNECTED)
        } finally { c.shutdown() }
    }

    @Test fun droppedConnectionIsRestoredAutomatically() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            val phases = java.util.concurrent.CopyOnWriteArrayList<ConnectionPhase>()
            c.addListener { phases.add(it.phase) }
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            env.core.handler.closeAll(Ucp.CLOSE_SHUTDOWN, "test drop")
            waitFor(what = "reconnecting seen") { ConnectionPhase.RECONNECTING in phases }
            waitFor(8_000, "reconnected") { phases.lastOrNull() == ConnectionPhase.CONNECTED && phases.count { it == ConnectionPhase.CONNECTED } >= 2 }
            c.load(url); waitFor { c.state.snapshot.status == PlaybackStatus.PLAYING }
        } finally { c.shutdown() }
    }

    @Test fun gaveUpWhenTheTvStaysAway() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = SenderController(id, connectTimeoutMs = 500, heartbeatMs = 10_000, reconnectDelaysMs = listOf(50L, 50L))
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            env.core.stop()
            c.awaitPhase(ConnectionPhase.FAILED, 10_000)
            assertEquals(FailureKind.UNREACHABLE, c.state.failure)
        } finally { c.shutdown() }
    }

    @Test fun userDisconnectDoesNotTriggerReconnect() = withEnv { env ->
        val id = SoftwareSenderIdentity.generate(); val r = pair(env, id, Role.CONTROLLER)
        val c = controller(id)
        try {
            c.connect(r); c.awaitPhase(ConnectionPhase.CONNECTED)
            c.disconnect(); c.awaitPhase(ConnectionPhase.DISCONNECTED)
            Thread.sleep(700)
            assertEquals(ConnectionPhase.DISCONNECTED, c.state.phase)
            waitFor { env.core.connectedSenders().isEmpty() }
        } finally { c.shutdown() }
    }

    @Test fun extrapolatedPositionFollowsPlaybackOnlyWhileTheTvPlays() {
        val playing = SenderState(
            snapshot = PlaybackSnapshot(status = PlaybackStatus.PLAYING, isPlaying = true, positionMs = 10_000, speed = 2f, durationMs = 20_000),
            snapshotReceivedAtMs = 1_000,
        )
        assertEquals(12_000, playing.estimatedPositionMs(2_000))
        assertEquals(20_000, playing.estimatedPositionMs(100_000), "never past the end")
        assertEquals(10_000, playing.estimatedPositionMs(0), "clock going backwards is harmless")
        val paused = playing.copy(snapshot = playing.snapshot.copy(status = PlaybackStatus.PAUSED, isPlaying = false))
        assertEquals(10_000, paused.estimatedPositionMs(50_000))
    }

    // ---- receiver store -----------------------------------------------------------------------------------

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val dir = File.createTempFile("ucs", "").let { it.delete(); it.mkdirs(); it }
    private val file = File(dir, "receivers.bin")
    private fun store() = EncryptedFileReceiverStore(file, AesGcmAead { key })
    private fun rec(n: String, fp: Char = 'a') = PairedReceiver(fp.toString().repeat(64), "dev$fp", n, listOf("10.0.0.5"), 8443, "/ucp", "CONTROLLER", 5)

    @Test fun storeRoundTripReplaceAndRemove() {
        val s = store(); s.save(rec("Living Room", 'a')); s.save(rec("Bedroom", 'b')); s.save(rec("Living Room 2", 'a'))
        val s2 = store()
        assertEquals(LoadOutcome.LOADED, s2.lastLoadOutcome)
        assertEquals(listOf("Living Room 2", "Bedroom"), s2.all().map { it.name })
        assertTrue(s2.remove("b".repeat(64))); assertFalse(s2.remove("b".repeat(64)))
        assertEquals(1, store().all().size)
    }

    @Test fun storeFileHasNoPlaintext() {
        store().save(rec("UniqueTvName"))
        val raw = String(file.readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains("UniqueTvName")); assertFalse(raw.contains("a".repeat(64)))
    }

    @Test fun tamperedOrForeignStoreFilesAreQuarantined() {
        store().save(rec("TV"))
        val bytes = file.readBytes(); bytes[bytes.size - 2] = (bytes[bytes.size - 2].toInt() xor 7).toByte(); file.writeBytes(bytes)
        val s = store(); assertEquals(LoadOutcome.CORRUPT_RESET, s.lastLoadOutcome); assertTrue(s.all().isEmpty())
        assertTrue(File(file.path + ".corrupt").exists())
        s.save(rec("TV2")); assertEquals(1, store().all().size)
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        assertEquals(LoadOutcome.CORRUPT_RESET, EncryptedFileReceiverStore(file, AesGcmAead { other }).lastLoadOutcome)
    }

    @Test fun storeRecordCodecRejectsInconsistentRecords() {
        assertNotNull(PairedReceiverJson.decode(PairedReceiverJson.encode(rec("TV"))))
        assertNull(PairedReceiverJson.decode(PairedReceiverJson.encode(rec("TV").copy(fingerprint = "zz".repeat(32)))))
        assertNull(PairedReceiverJson.decode(PairedReceiverJson.encode(rec("TV").copy(port = 0))))
        assertNull(PairedReceiverJson.decode("{not json"))
    }
}
