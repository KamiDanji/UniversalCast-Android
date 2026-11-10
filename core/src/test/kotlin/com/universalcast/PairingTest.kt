package com.universalcast

import com.universalcast.identity.*
import com.universalcast.pairing.*
import com.universalcast.security.*
import com.universalcast.ucp.*
import org.junit.Test
import kotlin.test.*

/** Drives PairingManager exactly as a (possibly hostile) sender would, without any network. */
class PairingTest {
    private val time = FakeTimeSource()
    private val receiver = TestPki.receiverIdentity()
    private val registry = InMemorySenderRegistry()
    private val mgr = PairingManager(receiver, registry, time, { listOf("10.0.0.2") }, { 8443 }, { "Living Room TV" })
    private val outcomes = ArrayList<Pair<String, PairingOutcome>>()

    init {
        mgr.addListener(object : PairingListener {
            override fun onPairingChanged(view: PairingView?) {}
            override fun onPairingFinished(connectionId: String, outcome: PairingOutcome) {
                outcomes.add(connectionId to outcome)
            }
        })
    }

    private class Sim(val sender: SoftwareSenderIdentity, val bootstrap: PairingBootstrap, secret: ByteArray = bootstrap.secret) {
        val eph = EcKeys.generateKeyPair()
        val nonce = SecureRandoms.bytes(32)
        val t0 = TranscriptInput(bootstrap.fingerprint, bootstrap.pairingId, sender.publicKeySpki, eph.public.encoded, nonce)
        val bootMac = PairingCrypto.bootstrapMac(secret, t0)
        var signer: SenderIdentity = sender
        var pairingId = bootstrap.pairingId
        fun fields(): Fields = Fields(
            mapOf(
                "pairingId" to pairingId, "senderName" to "Phone",
                "senderPublicKey" to Bytes.b64(sender.publicKeySpki), "ephemeralPublicKey" to Bytes.b64(eph.public.encoded),
                "senderNonce" to Bytes.b64(nonce), "bootstrapMac" to Bytes.b64(bootMac),
                "signature" to Bytes.b64(signer.sign(PairingCrypto.senderProofData(t0, bootMac))),
            )
        )

        lateinit var derived: PairingCrypto.Derived
        fun finish(resp: PairResponse) {
            val f = Fields(resp.payload)
            val t = TranscriptInput(bootstrap.fingerprint, bootstrap.pairingId, sender.publicKeySpki, eph.public.encoded, nonce,
                f.bytes("ephemeralPublicKey"), f.bytes("receiverNonce"))
            assertTrue(EcKeys.verify(f.bytes("receiverPublicKey"), PairingCrypto.receiverProofData(t), f.bytes("signature")))
            derived = PairingCrypto.derive(EcKeys.agree(eph.private, f.bytes("ephemeralPublicKey")), t)
        }

        fun confirmFields() = Fields(mapOf("confirmMac" to Bytes.b64(PairingCrypto.senderConfirmMac(derived.confirmKey, derived.transcriptHash))))
    }

    private fun code(block: () -> Unit) = assertFailsWith<UcpException> { block() }.code

    @Test fun happyPathRequiresEveryStepAndPersists() {
        val b = mgr.startPairing()
        assertEquals(PairingState.WAITING_FOR_SENDER, mgr.view()!!.state)
        assertEquals(120_000L, mgr.view()!!.remainingMs)
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        val v = mgr.view()!!
        assertEquals(PairingState.AWAITING_VERIFICATION, v.state)
        assertEquals(sim.derived.sas, v.sas, "receiver and sender derive the same SAS")
        assertEquals("Phone", v.senderName)

        assertFalse(mgr.approve(Role.CONTROLLER), "cannot approve before anything is verified")
        assertTrue(mgr.userConfirmSas(true))
        assertFalse(mgr.approve(Role.CONTROLLER), "cannot approve before the sender key confirmation")
        assertNull(registry.find(sim.sender.senderId))
        mgr.handleSenderConfirm("c1", sim.confirmFields())
        assertEquals(PairingState.READY_FOR_APPROVAL, mgr.view()!!.state)
        assertTrue(mgr.approve(Role.OBSERVER))

        val saved = registry.find(sim.sender.senderId)!!
        assertEquals(Role.OBSERVER, saved.role)
        assertContentEquals(sim.sender.publicKeySpki, saved.publicKeySpki)
        assertEquals(PairingState.APPROVED, mgr.view()!!.state)
        val (conn, outcome) = outcomes.single()
        assertEquals("c1", conn); assertEquals("approved", outcome.status); assertEquals(Role.OBSERVER, outcome.role)
        assertContentEquals(PairingCrypto.resultMac(sim.derived.confirmKey, sim.derived.transcriptHash, "approved", "OBSERVER"), outcome.mac)
        assertFalse(mgr.approve(Role.CONTROLLER), "approval is single use")
    }

    @Test fun userAlwaysHasToApproveSoNothingIsAutoApproved() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        mgr.handleSenderConfirm("c1", sim.confirmFields())
        assertEquals(PairingState.AWAITING_VERIFICATION, mgr.view()!!.state)
        assertTrue(registry.all().isEmpty())
    }

    @Test fun windowExpiresAfter120Seconds() {
        val b = mgr.startPairing()
        time.advance(119_999)
        assertEquals(1L, mgr.view()!!.remainingMs)
        time.advance(1)
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        assertEquals(ErrorCode.PAIRING_EXPIRED, code { mgr.handleRequest("c1", sim.fields()) })
        assertEquals(PairingState.EXPIRED, mgr.view()!!.state)
        assertTrue(registry.all().isEmpty())
    }

    @Test fun expiryMidFlightBlocksApproval() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        mgr.handleSenderConfirm("c1", sim.confirmFields())
        mgr.userConfirmSas(true)
        time.advance(120_001)
        assertFalse(mgr.approve(Role.CONTROLLER))
        assertEquals(PairingState.EXPIRED, mgr.view()!!.state)
        assertTrue(registry.all().isEmpty())
        assertEquals("expired", outcomes.single().second.status)
    }

    @Test fun tickExpiresAndNotifiesWaitingSender() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        time.advance(121_000)
        mgr.tick()
        assertEquals(PairingState.EXPIRED, mgr.view()!!.state)
        assertEquals("expired", outcomes.single().second.status)
    }

    @Test fun invalidBootstrapSecretIsRejectedAndBurnsTheSession() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b, secret = SecureRandoms.bytes(32))
        assertEquals(ErrorCode.PAIRING_INVALID_PROOF, code { mgr.handleRequest("c1", sim.fields()) })
        assertEquals(PairingState.FAILED, mgr.view()!!.state)
        val good = Sim(SoftwareSenderIdentity.generate(), b)
        assertEquals(ErrorCode.PAIRING_NOT_ACTIVE, code { mgr.handleRequest("c2", good.fields()) }, "a failed session cannot be retried")
        assertTrue(registry.all().isEmpty())
    }

    @Test fun senderProofSignedByAnotherKeyIsRejected() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.signer = SoftwareSenderIdentity.generate()
        assertEquals(ErrorCode.PAIRING_INVALID_PROOF, code { mgr.handleRequest("c1", sim.fields()) })
        assertTrue(registry.all().isEmpty())
    }

    @Test fun replayedPairRequestIsRejected() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        val fields = sim.fields()
        mgr.handleRequest("c1", fields)
        assertEquals(ErrorCode.PAIRING_NOT_ACTIVE, code { mgr.handleRequest("c2", fields) })
    }

    @Test fun unknownPairingIdIsIgnoredWithoutKillingTheSession() {
        val b = mgr.startPairing()
        val stray = Sim(SoftwareSenderIdentity.generate(), b)
        stray.pairingId = Bytes.b64(SecureRandoms.bytes(16))
        assertEquals(ErrorCode.PAIRING_NOT_ACTIVE, code { mgr.handleRequest("evil", stray.fields()) })
        assertEquals(PairingState.WAITING_FOR_SENDER, mgr.view()!!.state)
        val real = Sim(SoftwareSenderIdentity.generate(), b)
        real.finish(mgr.handleRequest("c1", real.fields()))
    }

    @Test fun noPairingActiveIsAnError() {
        val b = mgr.startPairing(); mgr.reject(); mgr.dismiss()
        assertEquals(ErrorCode.PAIRING_NOT_ACTIVE, code { mgr.handleRequest("c", Sim(SoftwareSenderIdentity.generate(), b).fields()) })
    }

    @Test fun rejectOnReceiverNeverSavesAndTellsSender() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        mgr.handleSenderConfirm("c1", sim.confirmFields()); mgr.userConfirmSas(true)
        assertTrue(mgr.reject())
        assertEquals(PairingState.REJECTED, mgr.view()!!.state)
        assertTrue(registry.all().isEmpty())
        assertEquals("rejected", outcomes.single().second.status)
        assertFalse(mgr.approve(Role.CONTROLLER))
    }

    @Test fun sasMismatchAbortsPairing() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        assertTrue(mgr.userConfirmSas(false))
        assertEquals(PairingState.FAILED, mgr.view()!!.state)
        assertFalse(mgr.approve(Role.CONTROLLER))
        assertTrue(registry.all().isEmpty())
        assertEquals("failed", outcomes.single().second.status)
    }

    @Test fun wrongSenderConfirmationMacFailsPairing() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        val bad = Fields(mapOf("confirmMac" to Bytes.b64(ByteArray(32))))
        assertEquals(ErrorCode.PAIRING_INVALID_PROOF, code { mgr.handleSenderConfirm("c1", bad) })
        assertEquals(PairingState.FAILED, mgr.view()!!.state)
    }

    @Test fun confirmFromAnotherConnectionIsRefused() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        assertEquals(ErrorCode.INVALID_STATE, code { mgr.handleSenderConfirm("intruder", sim.confirmFields()) })
    }

    @Test fun senderDisconnectFailsPairing() {
        val b = mgr.startPairing()
        val sim = Sim(SoftwareSenderIdentity.generate(), b)
        sim.finish(mgr.handleRequest("c1", sim.fields()))
        mgr.onConnectionClosed("c1")
        assertEquals(PairingState.FAILED, mgr.view()!!.state)
    }

    @Test fun newPairingSupersedesOldOne() {
        val b1 = mgr.startPairing()
        val b2 = mgr.startPairing()
        assertNotEquals(b1.pairingId, b2.pairingId)
        val sim = Sim(SoftwareSenderIdentity.generate(), b1)
        assertEquals(ErrorCode.PAIRING_NOT_ACTIVE, code { mgr.handleRequest("c", sim.fields()) })
    }

    @Test fun bootstrapUriRoundTripAndMalformedInputs() {
        val b = mgr.startPairing()
        val parsed = PairingBootstrap.parse(b.toUri())
        assertEquals(b.fingerprint, parsed.fingerprint); assertEquals(b.pairingId, parsed.pairingId)
        assertContentEquals(b.secret, parsed.secret); assertEquals(b.expiresAtMs, parsed.expiresAtMs)
        assertEquals(listOf("10.0.0.2"), parsed.addresses); assertEquals(8443, parsed.port); assertEquals("Living Room TV", parsed.deviceName)
        for (bad in listOf("", "http://x", PairingBootstrap.PREFIX, PairingBootstrap.PREFIX + "!!!", PairingBootstrap.PREFIX + Bytes.b64("{}".toByteArray()),
            PairingBootstrap.PREFIX + Bytes.b64("""{"v":1}""".toByteArray()), b.toUri() + "x".repeat(5000))) {
            assertFailsWith<IllegalArgumentException>("should reject '${bad.take(30)}'") { PairingBootstrap.parse(bad) }
        }
    }

    @Test fun senderNamesAreSanitised() {
        assertEquals("Phone", PairingManager.sanitizeName("  Pho\u0000ne\n "))
        assertEquals("Unknown device", PairingManager.sanitizeName("\u0001\u0002"))
        assertEquals(48, PairingManager.sanitizeName("x".repeat(200)).length)
    }
}
