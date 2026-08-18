package co.twinotify.core.pairing.lan

import java.security.MessageDigest
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePairingCoordinatorTest {
    @Test fun `both roles reach complete only after both confirmations and signatures`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()

        assertEquals(OfflinePairingState.VERIFY_CODE, pair.initiator.status.state)
        assertEquals(OfflinePairingState.VERIFY_CODE, pair.joiner.status.state)
        pair.initiator.confirmLocally()
        pair.pump()
        assertEquals(OfflinePairingState.LOCAL_CONFIRMED, pair.initiator.status.state)
        assertEquals(0, pair.initiatorStore.commits.size)

        pair.joiner.confirmLocally()
        pair.pump()

        assertEquals(OfflinePairingState.COMPLETE, pair.initiator.status.state)
        assertEquals(OfflinePairingState.COMPLETE, pair.joiner.status.state)
        assertEquals(1, pair.initiatorStore.commits.size)
        assertEquals(1, pair.joinerStore.commits.size)
        assertTrue(pair.initiatorStates.containsAll(listOf(
            OfflinePairingState.ADVERTISING, OfflinePairingState.TLS_AUTHENTICATED,
            OfflinePairingState.VERIFY_CODE, OfflinePairingState.LOCAL_CONFIRMED,
            OfflinePairingState.MUTUALLY_SIGNED, OfflinePairingState.COMMITTED, OfflinePairingState.COMPLETE,
        )))
        assertTrue(pair.joinerStates.containsAll(listOf(
            OfflinePairingState.RESOLVING, OfflinePairingState.TLS_AUTHENTICATED,
            OfflinePairingState.VERIFY_CODE, OfflinePairingState.LOCAL_CONFIRMED,
            OfflinePairingState.MUTUALLY_SIGNED, OfflinePairingState.COMMITTED, OfflinePairingState.COMPLETE,
        )))
    }

    @Test fun `peer signature received before local confirmation is a single bounded provisional value`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        pair.joiner.confirmLocally()
        pair.pump()

        assertEquals(OfflinePairingState.VERIFY_CODE, pair.initiator.status.state)
        assertEquals(64, pair.initiator.provisionalSignatureSizeForTest())
        assertEquals(0, pair.initiatorStore.commits.size)

        pair.initiator.confirmLocally()
        pair.pump()
        assertEquals(OfflinePairingState.COMPLETE, pair.initiator.status.state)
        assertEquals(0, pair.initiator.provisionalSignatureSizeForTest())
    }

    @Test fun `transcript key pin and session mismatch abort without committing`() {
        val cases = listOf(
            { p: PairHarness -> p.joiner.onTlsAuthenticated(bytes(99)) },
            { p: PairHarness -> p.initiator.onTlsAuthenticated(p.joinerIdentity.tlsSpkiSha256); p.initiator.onPeerFrame(p.joinerHello(sessionId = UUID.randomUUID().toString())) },
            { p: PairHarness -> p.initiator.onTlsAuthenticated(p.joinerIdentity.tlsSpkiSha256); p.initiator.onPeerFrame(p.joinerHello(tlsPin = bytes(48))) },
        )
        cases.forEach { exercise ->
            val pair = PairHarness()
            pair.startOnly()
            exercise(pair)
            val target = if (pair.joiner.status.error != null) pair.joiner else pair.initiator
            val targetPort = if (target === pair.joiner) pair.joinerPort else pair.initiatorPort
            assertEquals(OfflinePairingState.IDLE, target.status.state)
            assertEquals(OfflinePairingError.IDENTITY_MISMATCH, target.status.error)
            assertEquals(0, pair.initiatorStore.commits.size)
            assertTrue(targetPort.closed)
        }
    }

    @Test fun `conflicting duplicate frame aborts while exact duplicate is idempotent`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        val before = pair.initiator.status
        pair.initiator.onPeerFrame(pair.joinerHello())
        assertEquals(before.state, pair.initiator.status.state)

        pair.initiator.onPeerFrame(pair.joinerHello(nonce = bytes(61)))
        assertEquals(OfflinePairingState.IDLE, pair.initiator.status.state)
        assertEquals(OfflinePairingError.IDENTITY_MISMATCH, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
    }

    @Test fun `exact duplicate signature is idempotent and conflicting duplicate aborts`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        val signature = pair.joinerSignature()

        pair.initiator.onPeerFrame(signature)
        pair.initiator.onPeerFrame(signature)
        assertEquals(OfflinePairingState.VERIFY_CODE, pair.initiator.status.state)
        assertEquals(64, pair.initiator.provisionalSignatureSizeForTest())

        pair.initiator.onPeerFrame(OfflinePairingFrame.Signature(pair.qr.sessionId, bytes64(93)))
        assertEquals(OfflinePairingState.IDLE, pair.initiator.status.state)
        assertEquals(OfflinePairingError.IDENTITY_MISMATCH, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
    }

    @Test fun `reentrant authentication during initial advertise sees fully assigned ceremony`() {
        val pair = PairHarness()
        pair.initiatorPort.onAdvertise = {
            pair.initiator.onTlsAuthenticated(pair.joinerIdentity.tlsSpkiSha256)
        }

        pair.initiator.start(pair.qr, bytes(10))

        assertEquals(OfflinePairingState.TLS_AUTHENTICATED, pair.initiator.status.state)
        assertTrue(pair.initiatorPort.outbound.single() is OfflinePairingFrame.Hello)
        assertNull(pair.initiator.status.error)
    }

    @Test fun `status cancellation at mutually signed is terminal before commit`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        pair.initiator.onPeerFrame(pair.joinerSignature())
        pair.initiatorStatusHook = { status ->
            if (status.state == OfflinePairingState.MUTUALLY_SIGNED) pair.initiator.cancel()
        }

        pair.initiator.confirmLocally()

        assertEquals(OfflinePairingError.CANCELLED, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
        assertEquals(1, pair.initiatorPort.closeCount)
        assertFalse(pair.initiatorStates.contains(OfflinePairingState.COMPLETE))
    }

    @Test fun `port send cancellation at mutually signed prevents commit and complete`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        pair.initiator.onPeerFrame(pair.joinerSignature())
        pair.initiatorPort.onSend = { frame ->
            if (frame is OfflinePairingFrame.Signature) {
                assertEquals(OfflinePairingState.MUTUALLY_SIGNED, pair.initiator.status.state)
                pair.initiator.cancel()
            }
        }

        pair.initiator.confirmLocally()

        assertEquals(OfflinePairingError.CANCELLED, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
        assertEquals(1, pair.initiatorPort.closeCount)
        assertFalse(pair.initiatorStates.contains(OfflinePairingState.COMPLETE))
    }

    @Test fun `commit callback cancellation cannot be overwritten by completion`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        pair.initiator.onPeerFrame(pair.joinerSignature())
        pair.initiatorStore.onCommit = { pair.initiator.cancel() }

        pair.initiator.confirmLocally()

        assertEquals(OfflinePairingError.CANCELLED, pair.initiator.status.error)
        assertEquals(1, pair.initiatorStore.commits.size)
        assertEquals(1, pair.initiatorPort.closeCount)
        assertFalse(pair.initiatorStates.contains(OfflinePairingState.COMPLETE))
    }

    @Test fun `expiry during signature send takes precedence and prevents persistence`() {
        val pair = PairHarness(lifetimeMillis = 10)
        pair.startAndAuthenticate()
        pair.initiator.onPeerFrame(pair.joinerSignature())
        pair.initiatorPort.onSend = { frame ->
            if (frame is OfflinePairingFrame.Signature) pair.clock.now = 10
        }

        pair.initiator.confirmLocally()

        assertEquals(OfflinePairingError.EXPIRED, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
        assertEquals(1, pair.initiatorPort.closeCount)
    }

    @Test fun `expiry during commit preparation prevents persistence`() {
        val clock = FakeClock()
        val crypto = AdvancingCrypto(clock, advanceDuringDerive = 10)
        val pair = PairHarness(lifetimeMillis = 10, clock = clock, crypto = crypto)
        pair.startAndAuthenticate()
        pair.initiator.onPeerFrame(pair.joinerSignature(crypto))

        pair.initiator.confirmLocally()

        assertEquals(OfflinePairingError.EXPIRED, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
        assertEquals(1, pair.initiatorPort.closeCount)
    }

    @Test fun `expiry during signing and verification aborts before later effects`() {
        val signClock = FakeClock()
        val signPair = PairHarness(lifetimeMillis = 10, clock = signClock, crypto = AdvancingCrypto(signClock, advanceDuringSign = 10))
        signPair.startAndAuthenticate()
        signPair.initiator.confirmLocally()
        assertEquals(OfflinePairingError.EXPIRED, signPair.initiator.status.error)
        assertFalse(signPair.initiatorPort.outbound.any { it is OfflinePairingFrame.Signature })
        assertEquals(0, signPair.initiatorStore.commits.size)

        val verifyClock = FakeClock()
        val verifyPair = PairHarness(lifetimeMillis = 10, clock = verifyClock, crypto = AdvancingCrypto(verifyClock, advanceDuringVerify = 10))
        verifyPair.startAndAuthenticate()
        verifyPair.initiator.onPeerFrame(verifyPair.joinerSignature(FakeCrypto))
        assertEquals(OfflinePairingError.EXPIRED, verifyPair.initiator.status.error)
        assertEquals(0, verifyPair.initiatorStore.commits.size)
    }

    @Test fun `external callbacks never execute while coordinator monitor is held`() {
        val crypto = LockCheckingCrypto()
        val pair = PairHarness(crypto = crypto)
        crypto.target = pair.initiator
        val check = { assertFalse(Thread.holdsLock(pair.initiator)) }
        pair.initiatorPort.onAdvertise = check
        pair.initiatorPort.onSend = { check() }
        pair.initiatorPort.onClose = check
        pair.initiatorStore.onExistingPeer = check
        pair.initiatorStore.onCommit = check
        pair.initiatorStatusHook = { check() }

        pair.startAndAuthenticate()
        pair.joiner.confirmLocally()
        pair.pump()
        pair.initiator.confirmLocally()
        pair.pump()

        assertEquals(OfflinePairingState.COMPLETE, pair.initiator.status.state)
    }

    @Test fun `cancel at exact deadline reports expired`() {
        val pair = PairHarness(lifetimeMillis = 10)
        pair.startAndAuthenticate()
        pair.clock.now = 10

        pair.initiator.cancel()

        assertEquals(OfflinePairingError.EXPIRED, pair.initiator.status.error)
        assertEquals(1, pair.initiatorPort.closeCount)
    }

    @Test fun `initiator deadline and joiner capped monotonic deadline expire independent of wall clock`() {
        val pair = PairHarness(lifetimeMillis = 10)
        pair.startOnly()
        pair.clock.now = 11
        pair.initiator.onTlsAuthenticated(pair.joinerIdentity.tlsSpkiSha256)
        assertEquals(OfflinePairingError.EXPIRED, pair.initiator.status.error)
        assertTrue(pair.initiatorPort.closed)

        val joining = PairHarness(lifetimeMillis = 10)
        joining.startOnly()
        joining.clock.now = 11
        joining.joiner.onTlsAuthenticated(joining.initiatorIdentity.tlsSpkiSha256)
        assertEquals(OfflinePairingError.EXPIRED, joining.joiner.status.error)
        assertTrue(joining.joinerPort.closed)
    }

    @Test fun `cancellation clears every active lifecycle state and closes transport`() {
        val pair = PairHarness()
        pair.initiator.cancel()
        assertCancellation(pair.initiator, pair.initiatorPort)

        listOf<PairHarness.() -> Unit>(
            { startOnly() },
            { startOnly(); initiator.onTlsAuthenticated(joinerIdentity.tlsSpkiSha256) },
            { startAndAuthenticate() },
            { startAndAuthenticate(); initiator.confirmLocally() },
        ).forEach { prepare ->
            val case = PairHarness()
            case.prepare()
            case.initiator.cancel()
            assertCancellation(case.initiator, case.initiatorPort)
        }
    }

    @Test fun `restart never treats an uncommitted session as trusted`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        pair.joiner.confirmLocally()
        pair.pump()
        assertEquals(0, pair.initiatorStore.commits.size)

        val restarted = pair.newInitiator()
        assertEquals(OfflinePairingState.IDLE, restarted.status.state)
        assertNull(restarted.status.sas)
        assertEquals(0, pair.initiatorStore.commits.size)
    }

    @Test fun `relay pair upgrade commits only if application identity exactly matches stored peer`() {
        val pair = PairHarness(existingPeer = existingPeer(bytes(71), bytes(72)))
        pair.startOnly()
        pair.initiator.onTlsAuthenticated(pair.joinerIdentity.tlsSpkiSha256)
        pair.initiator.onPeerFrame(pair.joinerHello())
        assertEquals(OfflinePairingState.IDLE, pair.initiator.status.state)
        assertEquals(OfflinePairingError.IDENTITY_MISMATCH, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
    }

    @Test fun `joining role rejects application identity that differs from scanned QR despite matching TLS pin`() {
        val pair = PairHarness()
        pair.startOnly()
        pair.joiner.onTlsAuthenticated(pair.initiatorIdentity.tlsSpkiSha256)
        val forged = LanPairingHello(
            deviceId = "dev-00000000-0000-0000-0000-000000000077",
            encryptionPublicKey = LanPairingBytes(bytes(77)),
            signingPublicKey = LanPairingBytes(bytes(78)),
            tlsSpkiSha256 = LanPairingBytes(pair.initiatorIdentity.tlsSpkiSha256),
            nonce = LanPairingBytes(bytes(79)),
        )
        pair.joiner.onPeerFrame(OfflinePairingFrame.Hello(pair.qr.sessionId, pair.qr.lifetimeMillis, forged))

        assertEquals(OfflinePairingState.IDLE, pair.joiner.status.state)
        assertEquals(OfflinePairingError.IDENTITY_MISMATCH, pair.joiner.status.error)
        assertEquals(0, pair.joinerStore.commits.size)
    }

    @Test fun `signature that does not authenticate the canonical transcript aborts`() {
        val pair = PairHarness()
        pair.startAndAuthenticate()
        pair.initiator.onPeerFrame(OfflinePairingFrame.Signature(pair.qr.sessionId, ByteArray(64) { 91 }))

        assertEquals(OfflinePairingState.IDLE, pair.initiator.status.state)
        assertEquals(OfflinePairingError.IDENTITY_MISMATCH, pair.initiator.status.error)
        assertEquals(0, pair.initiatorStore.commits.size)
    }

    @Test fun `status and errors remain bounded and secret free`() {
        val pair = PairHarness()
        pair.startOnly()
        pair.joiner.onTlsAuthenticated(bytes(99))
        val status = pair.joiner.status
        assertEquals("identity_mismatch", status.error?.code)
        assertTrue(status.toString().length < 256)
        assertFalse(status.toString().contains(pair.qr.sessionToken.copy().joinToString()))
        assertFalse(pair.initiatorEvents.any { it.contains(pair.qr.sessionToken.copy().joinToString()) })
    }

    private fun assertCancellation(coordinator: OfflinePairingCoordinator, port: FakePort) {
        assertEquals(OfflinePairingState.IDLE, coordinator.status.state)
        assertNull(coordinator.status.sas)
        assertEquals(0, coordinator.provisionalSignatureSizeForTest())
        assertTrue(port.closed)
    }
}

private class PairHarness(
    lifetimeMillis: Long = 60_000,
    existingPeer: OfflinePairingExistingPeer? = null,
    val clock: FakeClock = FakeClock(),
    private val crypto: OfflinePairingCrypto = FakeCrypto,
) {
    val initiatorIdentity = identity(1)
    val joinerIdentity = identity(2)
    val qr = qr(initiatorIdentity, lifetimeMillis)
    val initiatorPort = FakePort(clock)
    val joinerPort = FakePort(clock)
    val initiatorStore = FakeCommitter(existingPeer)
    val joinerStore = FakeCommitter()
    val initiatorEvents = mutableListOf<String>()
    val initiatorStates = mutableListOf<OfflinePairingState>()
    val joinerStates = mutableListOf<OfflinePairingState>()
    var initiatorStatusHook: ((OfflinePairingStatus) -> Unit)? = null
    val initiator = newInitiator()
    val joiner = OfflinePairingCoordinator(
        role = OfflinePairingRole.JOINER,
        localIdentity = joinerIdentity,
        port = joinerPort,
        committer = joinerStore,
        crypto = crypto,
        statusSink = { status -> joinerStates += status.state },
    )

    fun newInitiator() = OfflinePairingCoordinator(
        role = OfflinePairingRole.INITIATOR,
        localIdentity = initiatorIdentity,
        port = initiatorPort,
        committer = initiatorStore,
        crypto = crypto,
        statusSink = { status -> initiatorStates += status.state; initiatorEvents += status.toString(); initiatorStatusHook?.invoke(status) },
    )

    fun startOnly() {
        initiator.start(qr, bytes(10))
        joiner.start(qr, bytes(20))
    }

    fun startAndAuthenticate() {
        startOnly()
        initiator.onTlsAuthenticated(joinerIdentity.tlsSpkiSha256)
        joiner.onTlsAuthenticated(initiatorIdentity.tlsSpkiSha256)
        pump()
    }

    fun pump() {
        while (initiatorPort.outbound.isNotEmpty() || joinerPort.outbound.isNotEmpty()) {
            initiatorPort.outbound.removeFirstOrNull()?.let(joiner::onPeerFrame)
            joinerPort.outbound.removeFirstOrNull()?.let(initiator::onPeerFrame)
        }
    }

    fun joinerHello(
        sessionId: String = qr.sessionId,
        encryptionKey: ByteArray = joinerIdentity.encryptionPublicKey,
        tlsPin: ByteArray = joinerIdentity.tlsSpkiSha256,
        nonce: ByteArray = bytes(20),
    ) = OfflinePairingFrame.Hello(
        sessionId, qr.lifetimeMillis,
        LanPairingHello(joinerIdentity.deviceId, LanPairingBytes(encryptionKey), LanPairingBytes(joinerIdentity.signingPublicKey), LanPairingBytes(tlsPin), LanPairingBytes(nonce)),
    )

    fun joinerSignature(using: OfflinePairingCrypto = crypto): OfflinePairingFrame.Signature {
        val transcript = using.canonicalTranscript(
            LanPairingTranscript(qr.sessionId, qr.lifetimeMillis, qr.version, joinerHello().hello, initiatorHello()),
        )
        return OfflinePairingFrame.Signature(qr.sessionId, using.signTranscript(transcript, joinerIdentity.signingSecretKey))
    }

    private fun initiatorHello() = LanPairingHello(
        initiatorIdentity.deviceId, LanPairingBytes(initiatorIdentity.encryptionPublicKey), LanPairingBytes(initiatorIdentity.signingPublicKey),
        LanPairingBytes(initiatorIdentity.tlsSpkiSha256), LanPairingBytes(bytes(10)),
    )
}

private class FakeClock(var now: Long = 0)

private class FakePort(private val clock: FakeClock) : OfflinePairingPort {
    override fun monotonicMillis(): Long = clock.now
    val outbound = ArrayDeque<OfflinePairingFrame>()
    var closed = false
    var closeCount = 0
    var onAdvertise: (() -> Unit)? = null
    var onSend: ((OfflinePairingFrame) -> Unit)? = null
    var onClose: (() -> Unit)? = null
    override fun advertise(sessionId: String) { onAdvertise?.invoke() }
    override fun resolve(sessionId: String, expectedTlsSpkiSha256: ByteArray) = Unit
    override fun send(frame: OfflinePairingFrame) { outbound += frame; onSend?.invoke(frame) }
    override fun close() { onClose?.invoke(); closed = true; closeCount++ }
}

private class FakeCommitter(private val existing: OfflinePairingExistingPeer? = null) : OfflinePairingCommitter {
    val commits = mutableListOf<OfflinePairingCommit>()
    var onExistingPeer: (() -> Unit)? = null
    var onCommit: (() -> Unit)? = null
    override fun existingPeer(): OfflinePairingExistingPeer? { onExistingPeer?.invoke(); return existing }
    override fun commit(value: OfflinePairingCommit): Boolean { commits += value; onCommit?.invoke(); return true }
}

private class AdvancingCrypto(
    private val clock: FakeClock,
    private val advanceDuringDerive: Long? = null,
    private val advanceDuringSign: Long? = null,
    private val advanceDuringVerify: Long? = null,
) : OfflinePairingCrypto by FakeCrypto {
    override fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray {
        val result = FakeCrypto.derivePairSecret(sessionToken, transcript)
        advanceDuringDerive?.let { clock.now = it }
        return result
    }

    override fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray =
        FakeCrypto.signTranscript(transcript, secretKey).also { advanceDuringSign?.let { now -> clock.now = now } }

    override fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        FakeCrypto.verifyTranscript(transcript, signature, publicKey).also { advanceDuringVerify?.let { now -> clock.now = now } }
}

private class LockCheckingCrypto : OfflinePairingCrypto by FakeCrypto {
    var target: OfflinePairingCoordinator? = null
    private fun check() { target?.let { assertFalse(Thread.holdsLock(it)) } }
    override fun canonicalTranscript(value: LanPairingTranscript): ByteArray { check(); return FakeCrypto.canonicalTranscript(value) }
    override fun shortAuthenticationString(transcript: ByteArray): String { check(); return FakeCrypto.shortAuthenticationString(transcript) }
    override fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray { check(); return FakeCrypto.derivePairSecret(sessionToken, transcript) }
    override fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray { check(); return FakeCrypto.signTranscript(transcript, secretKey) }
    override fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean { check(); return FakeCrypto.verifyTranscript(transcript, signature, publicKey) }
}

private object FakeCrypto : OfflinePairingCrypto {
    override fun canonicalTranscript(value: LanPairingTranscript): ByteArray = LanPairingCodec.canonicalTranscript(value)
    override fun shortAuthenticationString(transcript: ByteArray): String = "123456"
    override fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(sessionToken + transcript)
    override fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(transcript + secretKey)
    override fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        signature.contentEquals(MessageDigest.getInstance("SHA-512").digest(transcript + ByteArray(64) { publicKey[0] }))
}

private fun identity(seed: Int) = OfflinePairingIdentity(
    deviceId = "dev-00000000-0000-0000-0000-${seed.toString().padStart(12, '0')}",
    displayName = "Device $seed",
    encryptionPublicKey = bytes(seed),
    signingPublicKey = bytes(seed + 10),
    signingSecretKey = ByteArray(64) { (seed + 10).toByte() },
    tlsSpkiSha256 = bytes(seed + 20),
)

private fun qr(identity: OfflinePairingIdentity, lifetime: Long) = LanPairingQr(
    version = 1, sessionId = "00000000-0000-0000-0000-000000000099", createdAtHintMillis = 0, lifetimeMillis = lifetime,
    deviceId = identity.deviceId, displayName = identity.displayName, encryptionPublicKey = LanPairingBytes(identity.encryptionPublicKey),
    signingPublicKey = LanPairingBytes(identity.signingPublicKey), tlsSpkiSha256 = LanPairingBytes(identity.tlsSpkiSha256), sessionToken = LanPairingBytes(bytes(88)),
)

private fun existingPeer(enc: ByteArray, sign: ByteArray) = OfflinePairingExistingPeer("dev-00000000-0000-0000-0000-000000000002", enc, sign)
private fun bytes(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }
private fun bytes64(seed: Int): ByteArray = ByteArray(64) { seed.toByte() }
