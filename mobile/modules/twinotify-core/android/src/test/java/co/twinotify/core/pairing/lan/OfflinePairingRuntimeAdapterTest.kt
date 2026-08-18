package co.twinotify.core.pairing.lan

import co.twinotify.core.OfflinePairingApiError
import co.twinotify.core.OfflinePairingApiPhase
import co.twinotify.core.OfflinePairingPublicStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import co.twinotify.core.defaultOfflinePairingRuntimeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePairingRuntimeAdapterTest {
    @Test
    fun nativeDefaultIsTheRealAndroidRuntimeFactory() {
        val factory = defaultOfflinePairingRuntimeFactory { error("context_not_needed") }

        assertTrue(factory is AndroidOfflinePairingRuntimeFactory)
    }

    @Test
    fun wifiPermissionAndUnavailableFailuresRemainDistinctAtTheApiBoundary() {
        assertEquals(
            OfflinePairingApiError.WIFI_PERMISSION_DENIED,
            PairingWifiNetworkException(PairingWifiNetworkFailure.PERMISSION_DENIED).toApiError(),
        )
        assertEquals(
            OfflinePairingApiError.WIFI_UNAVAILABLE,
            PairingWifiNetworkException(PairingWifiNetworkFailure.UNAVAILABLE).toApiError(),
        )
    }

    @Test
    fun actorMailboxIsCountAndByteBounded() = runTest {
        val mailbox = BoundedPairingActorMailbox<String>(maxEvents = 1, maxBytes = 4)

        assertTrue(mailbox.trySend("one", 4))
        assertFalse(mailbox.trySend("two", 1))
        assertEquals("one", mailbox.receive())
        assertFalse(mailbox.trySend("oversize", 5))
        mailbox.close()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun bothRolesProduceEqualSasAndCommitOnlyAfterBothConfirm() = runTest {
        val harness = RuntimeHarness(CoroutineScope(StandardTestDispatcher(testScheduler)))

        runCurrent()
        assertTrue("actor failures=${harness.failures}", harness.initiatorStatuses.isNotEmpty())
        assertTrue("actor failures=${harness.failures}", harness.joinerStatuses.isNotEmpty())
        assertEquals(harness.initiatorStatuses.toString(), OfflinePairingApiPhase.VERIFY_CODE, harness.initiatorStatuses.last().phase)
        assertEquals(harness.joinerStatuses.toString(), OfflinePairingApiPhase.VERIFY_CODE, harness.joinerStatuses.last().phase)
        assertEquals(harness.initiatorStatuses.last().sas, harness.joinerStatuses.last().sas)
        assertTrue(harness.initiatorCommitter.commits.isEmpty())
        assertTrue(harness.joinerCommitter.commits.isEmpty())

        harness.initiator.confirm()
        harness.joiner.confirm()
        runCurrent()

        assertEquals(1, harness.initiatorCommitter.commits.size)
        assertEquals(1, harness.joinerCommitter.commits.size)
        assertEquals(OfflinePairingApiPhase.COMPLETE, harness.initiatorStatuses.last().phase)
        assertEquals(OfflinePairingApiPhase.COMPLETE, harness.joinerStatuses.last().phase)
        assertEquals(1, harness.initiatorTransport.closeCount)
        assertEquals(1, harness.joinerTransport.closeCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun localCancellationIsDistinctFromAuthenticatedPeerRejectionAndCleansOnce() = runTest {
        val harness = RuntimeHarness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        runCurrent()
        assertTrue("actor failures=${harness.failures}", harness.initiatorStatuses.isNotEmpty())
        assertTrue("actor failures=${harness.failures}", harness.joinerStatuses.isNotEmpty())

        harness.initiator.cancel()
        runCurrent()

        assertEquals(OfflinePairingApiError.CANCELLED, harness.initiatorStatuses.last().error)
        assertEquals(OfflinePairingApiError.PEER_REJECTED, harness.joinerStatuses.last().error)
        assertEquals(1, harness.initiatorTransport.closeCount)
        assertEquals(1, harness.joinerTransport.closeCount)
        assertTrue(harness.initiatorCommitter.commits.isEmpty())
        assertTrue(harness.joinerCommitter.commits.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun selectedWifiLossFailsTheActorAndReleasesItsLeaseOnce() = runTest {
        val harness = RuntimeHarness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        runCurrent()

        harness.initiatorTransport.loseNetwork()
        runCurrent()

        assertEquals(OfflinePairingApiError.WIFI_UNAVAILABLE, harness.initiatorStatuses.last().error)
        assertEquals(1, harness.initiatorTransport.closeCount)
        assertTrue(harness.initiatorCommitter.commits.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun ceremonyDeadlineExpiresWithoutRequiringAnotherPeerFrame() = runTest {
        val harness = RuntimeHarness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        runCurrent()

        advanceTimeBy(300_000)
        runCurrent()

        assertEquals(OfflinePairingApiError.EXPIRED, harness.initiatorStatuses.last().error)
        assertEquals(1, harness.initiatorTransport.closeCount)
        assertTrue(harness.initiatorCommitter.commits.isEmpty())
    }
}

private class RuntimeHarness(scope: CoroutineScope) {
    private val dispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher
    private val initiatorIdentity = identity("dev-00000000-0000-0000-0000-000000000001", "Initiator", 1)
    private val joinerIdentity = identity("dev-00000000-0000-0000-0000-000000000002", "Joiner", 11)
    private val aToB = Channel<OfflinePairingFrame>(16)
    private val bToA = Channel<OfflinePairingFrame>(16)
    val initiatorTransport = MemoryTransport(MemoryConnection(bToA, aToB, joinerIdentity.tlsSpkiSha256))
    val joinerTransport = MemoryTransport(MemoryConnection(aToB, bToA, initiatorIdentity.tlsSpkiSha256))
    val initiatorCommitter = RecordingCommitter()
    val joinerCommitter = RecordingCommitter()
    val initiatorStatuses = mutableListOf<OfflinePairingPublicStatus>()
    val joinerStatuses = mutableListOf<OfflinePairingPublicStatus>()
    val failures = mutableListOf<Throwable?>()
    private val qr = LanPairingQr(
        1,
        UUID.randomUUID().toString(),
        1,
        300_000,
        initiatorIdentity.deviceId,
        initiatorIdentity.displayName,
        LanPairingBytes(initiatorIdentity.encryptionPublicKey),
        LanPairingBytes(initiatorIdentity.signingPublicKey),
        LanPairingBytes(initiatorIdentity.tlsSpkiSha256),
        LanPairingBytes(ByteArray(32) { 42 }),
    )
    val initiator = runtime(scope, OfflinePairingRole.INITIATOR, initiatorIdentity, initiatorTransport, initiatorCommitter, initiatorStatuses)
    val joiner = runtime(scope, OfflinePairingRole.JOINER, joinerIdentity, joinerTransport, joinerCommitter, joinerStatuses)

    init {
        initiator.job.invokeOnCompletion(failures::add)
        joiner.job.invokeOnCompletion(failures::add)
    }

    private fun runtime(
        scope: CoroutineScope,
        role: OfflinePairingRole,
        identity: OfflinePairingIdentity,
        transport: MemoryTransport,
        committer: RecordingCommitter,
        statuses: MutableList<OfflinePairingPublicStatus>,
    ) = OfflinePairingRuntimeAdapter(
        scope,
        role,
        qr,
        identity,
        committer,
        transport,
        ByteArray(32) { if (role == OfflinePairingRole.INITIATOR) 7 else 8 },
        if (role == OfflinePairingRole.INITIATOR) LanPairingCodec.encodeQr(qr) else null,
        statuses::add,
        TestRuntimeCrypto,
        monotonicMillis = { 1_000 },
        actorDispatcher = dispatcher,
    )

    private fun identity(deviceId: String, name: String, seed: Int) = OfflinePairingIdentity(
        deviceId,
        name,
        ByteArray(32) { (seed + it).toByte() },
        ByteArray(32) { (seed + 1 + it).toByte() },
        ByteArray(64) { (seed + 2 + it).toByte() },
        ByteArray(32) { (seed + 3 + it).toByte() },
    )
}

private class MemoryTransport(
    private val connection: RuntimePairingConnection,
) : OfflinePairingSessionTransport {
    var closeCount = 0
    private var onNetworkLost: (() -> Unit)? = null
    override suspend fun open(
        role: OfflinePairingRole,
        qr: LanPairingQr,
        onNetworkLost: () -> Unit,
    ): RuntimePairingConnection {
        this.onNetworkLost = onNetworkLost
        return connection
    }
    fun loseNetwork() = checkNotNull(onNetworkLost).invoke()
    override fun close() { closeCount++ }
}

private class MemoryConnection(
    private val inbound: Channel<OfflinePairingFrame>,
    private val outbound: Channel<OfflinePairingFrame>,
    pin: ByteArray,
) : RuntimePairingConnection {
    private val pin = pin.copyOf()
    override val peerSpkiSha256: ByteArray get() = pin.copyOf()
    override suspend fun read(): OfflinePairingFrame = inbound.receive()
    override fun write(frame: OfflinePairingFrame) {
        check(outbound.trySend(frame).isSuccess)
    }
    override fun close() = Unit
}

private class RecordingCommitter : OfflinePairingCommitter {
    val commits = mutableListOf<OfflinePairingCommit>()
    override fun existingPeer(): OfflinePairingExistingPeer? = null
    override fun commit(value: OfflinePairingCommit): Boolean {
        commits += value
        return true
    }
}

private object TestRuntimeCrypto : OfflinePairingCrypto {
    override fun canonicalTranscript(value: LanPairingTranscript): ByteArray = LanPairingCodec.canonicalTranscript(value)
    override fun shortAuthenticationString(transcript: ByteArray): String =
        ((MessageDigest.getInstance("SHA-256").digest(transcript)[0].toInt() and 0xff) % 1_000_000)
            .toString().padStart(6, '0')
    override fun derivePairSecret(sessionToken: ByteArray, transcript: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(sessionToken + transcript)
    override fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(transcript)
    override fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        signature.contentEquals(MessageDigest.getInstance("SHA-512").digest(transcript))
}
