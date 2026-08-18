package co.twinotify.core.pairing.lan

import co.twinotify.core.OfflinePairingApiError
import co.twinotify.core.OfflinePairingApiController
import co.twinotify.core.OfflinePairingRuntime
import co.twinotify.core.OfflinePairingRuntimeFactory
import co.twinotify.core.OfflinePairingApiPhase
import co.twinotify.core.OfflinePairingPublicStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import co.twinotify.core.defaultOfflinePairingRuntimeFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
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
    fun tlsPinTransportFailurePublishesBoundedPinErrorAndIdentityMismatchStaysApplicationScoped() = runBlocking {
        val identity = runtimeIdentity("dev-00000000-0000-0000-0000-000000000004", "Pin test", 31)
        val qr = runtimeQr(identity, lifetimeMillis = 5_000)
        val statuses = mutableListOf<OfflinePairingPublicStatus>()
        val runtime = OfflinePairingRuntimeAdapter(
            CoroutineScope(Job()),
            OfflinePairingRole.INITIATOR,
            qr,
            identity,
            RecordingCommitter(),
            FailingTransport(PairingTransportException(PairingTransportFailure.TLS_PIN_MISMATCH)),
            ByteArray(32) { 9 },
            LanPairingCodec.encodeQr(qr),
            statuses::add,
            TestRuntimeCrypto,
            monotonicMillis = { 1_000 },
            actorDispatcher = Dispatchers.IO,
        )

        withTimeout(1_000) { runtime.job.join() }

        val terminal = statuses.last()
        assertEquals("tls_pin_mismatch", terminal.error?.code)
        assertEquals("tls_pin_mismatch", terminal.toEventMap()["errorCode"])
        assertEquals(
            setOf("role", "phase", "sessionId", "errorCode", "peerDisplayName", "sas", "completed"),
            terminal.toEventMap().keys,
        )
        assertEquals("identity_mismatch", OfflinePairingError.IDENTITY_MISMATCH.toApiError().code)
    }

    @Test
    fun everyNsdSecurityBoundaryMapsToWifiPermissionDenied() {
        listOf("register", "discover", "resolve", "multicast").forEach { boundary ->
            assertEquals(
                "$boundary must preserve SecurityException",
                PairingTransportFailure.PERMISSION_DENIED,
                (mapPairingNsdThrowable(SecurityException(boundary)) as PairingTransportException).failure,
            )
        }
        assertEquals(
            PairingTransportFailure.NSD_FAILED,
            (mapPairingNsdThrowable(IllegalStateException("unavailable")) as PairingTransportException).failure,
        )
        val cancellation = kotlinx.coroutines.CancellationException("cancel")
        assertSame(cancellation, mapPairingNsdThrowable(cancellation))
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

    @Test
    fun blockingWriteCannotPinCeremonyPastDeadlineAndCleanup() = runBlocking {
        val identity = runtimeIdentity("dev-00000000-0000-0000-0000-000000000003", "Blocked", 21)
        val qr = runtimeQr(identity, lifetimeMillis = 50)
        val connection = BlockingWriteConnection(ByteArray(32) { 8 })
        val transport = MemoryTransport(connection)
        val statuses = mutableListOf<OfflinePairingPublicStatus>()
        val runtime = OfflinePairingRuntimeAdapter(
            CoroutineScope(Job()), OfflinePairingRole.INITIATOR, qr, identity, RecordingCommitter(), transport,
            ByteArray(32) { 4 }, LanPairingCodec.encodeQr(qr), statuses::add, TestRuntimeCrypto,
            monotonicMillis = { 1_000 }, actorDispatcher = Dispatchers.IO,
        )

        try {
            withTimeout(1_000) { runtime.job.join() }
        } finally {
            connection.close()
        }

        assertEquals(OfflinePairingApiError.EXPIRED, statuses.last().error)
        assertEquals(1, connection.closeCount)
        assertEquals(1, transport.closeCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun controllerCancellationSerializesOneCancelBeforeClosingRealAdapter() = runTest {
        val harness = RuntimeHarness(CoroutineScope(StandardTestDispatcher(testScheduler)))
        runCurrent()
        val factory = object : OfflinePairingRuntimeFactory {
            override fun start(
                scope: CoroutineScope,
                displayName: String,
                statusSink: (OfflinePairingPublicStatus) -> Unit,
            ): OfflinePairingRuntime {
                harness.initiatorObserver = statusSink
                return harness.initiator
            }
            override fun join(
                scope: CoroutineScope,
                qr: LanPairingQr,
                displayName: String,
                statusSink: (OfflinePairingPublicStatus) -> Unit,
            ): OfflinePairingRuntime = error("unused")
        }
        val controller = OfflinePairingApiController(this, factory) {}
        controller.start("Controller")

        controller.cancel(harness.initiator.sessionId)
        runCurrent()

        assertEquals(1, harness.initiatorConnection.cancelWriteCount)
        assertEquals(OfflinePairingApiError.PEER_REJECTED, harness.joinerStatuses.last().error)
        assertEquals(1, harness.initiatorTransport.closeCount)
        assertEquals(1, harness.joinerTransport.closeCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun controllerCancellationHasPriorityOverSaturatedRealAdapterMailbox() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher)
        val identity = runtimeIdentity("dev-00000000-0000-0000-0000-000000000003", "Saturated", 31)
        val qr = runtimeQr(identity, lifetimeMillis = 60_000)
        val connection = SaturatingConnection(ByteArray(32) { 8 })
        val transport = MemoryTransport(connection)
        var controllerObserver: ((OfflinePairingPublicStatus) -> Unit)? = null
        val runtime = OfflinePairingRuntimeAdapter(
            scope, OfflinePairingRole.INITIATOR, qr, identity, RecordingCommitter(), transport,
            ByteArray(32) { 4 }, LanPairingCodec.encodeQr(qr),
            { controllerObserver?.invoke(it) }, TestRuntimeCrypto,
            monotonicMillis = { 1_000 }, actorDispatcher = dispatcher, eventCapacity = 1,
        )
        val factory = object : OfflinePairingRuntimeFactory {
            override fun start(
                scope: CoroutineScope,
                displayName: String,
                statusSink: (OfflinePairingPublicStatus) -> Unit,
            ): OfflinePairingRuntime {
                controllerObserver = statusSink
                return runtime
            }
            override fun join(
                scope: CoroutineScope,
                qr: LanPairingQr,
                displayName: String,
                statusSink: (OfflinePairingPublicStatus) -> Unit,
            ): OfflinePairingRuntime = error("unused")
        }
        val controller = OfflinePairingApiController(this, factory) {}
        controller.start("Controller")
        runCurrent()
        val remote = runtimeIdentity("dev-00000000-0000-0000-0000-000000000077", "Remote", 41)
        connection.queueInbound(
            OfflinePairingFrame.Hello(
                qr.sessionId,
                qr.lifetimeMillis,
                LanPairingHello(
                    remote.deviceId,
                    remote.displayName,
                    LanPairingBytes(remote.encryptionPublicKey),
                    LanPairingBytes(remote.signingPublicKey),
                    LanPairingBytes(connection.peerSpkiSha256!!),
                    LanPairingBytes(ByteArray(32) { 9 }),
                ),
            ),
        )
        runCurrent()

        val cancelling = launch { controller.cancel(runtime.sessionId) }
        val duplicate = launch { controller.cancel(runtime.sessionId) }
        runCurrent()
        connection.releaseFirstWrite()
        runCurrent()
        cancelling.join()
        duplicate.join()

        assertEquals(1, connection.cancelWriteCount)
        assertEquals(listOf("hello", "cancel"), connection.writeKinds)
        val cancel = connection.writtenFrames.single { it is OfflinePairingFrame.Cancel } as OfflinePairingFrame.Cancel
        assertTrue(TestRuntimeCrypto.verifyCancelAuthenticator(qr.sessionToken.copy(), qr.sessionId, cancel.authenticator))
        assertEquals(1, transport.closeCount)
        assertEquals(1, connection.closeCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancelWaitCoversBoundedActiveAndTerminalWritesBeforeCleanupFallback() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val identity = runtimeIdentity("dev-00000000-0000-0000-0000-000000000003", "Bounded", 51)
        val qr = runtimeQr(identity, lifetimeMillis = 60_000)
        val connection = BudgetedWriteConnection(ByteArray(32) { 8 })
        val transport = MemoryTransport(connection)
        val runtime = OfflinePairingRuntimeAdapter(
            CoroutineScope(dispatcher), OfflinePairingRole.INITIATOR, qr, identity, RecordingCommitter(), transport,
            ByteArray(32) { 4 }, LanPairingCodec.encodeQr(qr), {}, TestRuntimeCrypto,
            monotonicMillis = { 1_000 }, actorDispatcher = dispatcher,
        )
        runCurrent()

        val cancelling = launch { runtime.cancel() }
        runCurrent()
        advanceTimeBy(PAIRING_WRITE_TIMEOUT_MILLIS * 2 + 1)
        runCurrent()
        cancelling.join()

        assertEquals(listOf("hello", "cancel"), connection.writeKinds)
        assertEquals(1, connection.closeCount)
        assertEquals(1, transport.closeCount)
    }
}

private class RuntimeHarness(scope: CoroutineScope) {
    private val dispatcher = scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as kotlinx.coroutines.CoroutineDispatcher
    private val initiatorIdentity = identity("dev-00000000-0000-0000-0000-000000000001", "Initiator", 1)
    private val joinerIdentity = identity("dev-00000000-0000-0000-0000-000000000002", "Joiner", 11)
    private val aToB = Channel<OfflinePairingFrame>(16)
    private val bToA = Channel<OfflinePairingFrame>(16)
    val initiatorConnection = MemoryConnection(bToA, aToB, joinerIdentity.tlsSpkiSha256)
    val initiatorTransport = MemoryTransport(initiatorConnection)
    val joinerTransport = MemoryTransport(MemoryConnection(aToB, bToA, initiatorIdentity.tlsSpkiSha256))
    val initiatorCommitter = RecordingCommitter()
    val joinerCommitter = RecordingCommitter()
    val initiatorStatuses = mutableListOf<OfflinePairingPublicStatus>()
    val joinerStatuses = mutableListOf<OfflinePairingPublicStatus>()
    val failures = mutableListOf<Throwable?>()
    var initiatorObserver: ((OfflinePairingPublicStatus) -> Unit)? = null
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
        { status ->
            statuses += status
            if (role == OfflinePairingRole.INITIATOR) initiatorObserver?.invoke(status)
        },
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

private class FailingTransport(private val failure: Throwable) : OfflinePairingSessionTransport {
    override suspend fun open(
        role: OfflinePairingRole,
        qr: LanPairingQr,
        onNetworkLost: () -> Unit,
    ): RuntimePairingConnection = throw failure

    override fun close() = Unit
}

private class MemoryConnection(
    private val inbound: Channel<OfflinePairingFrame>,
    private val outbound: Channel<OfflinePairingFrame>,
    pin: ByteArray,
) : RuntimePairingConnection {
    private val pin = pin.copyOf()
    override val peerSpkiSha256: ByteArray get() = pin.copyOf()
    override suspend fun read(): OfflinePairingFrame = inbound.receive()
    var cancelWriteCount = 0
    override suspend fun write(frame: OfflinePairingFrame) {
        if (frame is OfflinePairingFrame.Cancel) cancelWriteCount++
        check(outbound.trySend(frame).isSuccess)
    }
    override fun close() = Unit
}

private class BlockingWriteConnection(pin: ByteArray) : RuntimePairingConnection {
    private val pin = pin.copyOf()
    private val released = java.util.concurrent.CountDownLatch(1)
    private val closes = java.util.concurrent.atomic.AtomicInteger(0)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override val peerSpkiSha256: ByteArray get() = pin.copyOf()
    val closeCount: Int get() = closes.get()
    override suspend fun read(): OfflinePairingFrame = kotlinx.coroutines.awaitCancellation()
    override suspend fun write(frame: OfflinePairingFrame) { released.await() }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closes.incrementAndGet()
            released.countDown()
        }
    }
}

private class SaturatingConnection(pin: ByteArray) : RuntimePairingConnection {
    private val pin = pin.copyOf()
    private val firstWrite = CompletableDeferred<Unit>()
    private val inbound = Channel<OfflinePairingFrame>(1)
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override val peerSpkiSha256: ByteArray get() = pin.copyOf()
    val writeKinds = mutableListOf<String>()
    val writtenFrames = mutableListOf<OfflinePairingFrame>()
    var cancelWriteCount = 0
    var closeCount = 0
    override suspend fun read(): OfflinePairingFrame = inbound.receive()
    override suspend fun write(frame: OfflinePairingFrame) {
        if (writeKinds.isEmpty()) firstWrite.await()
        writeKinds += when (frame) {
            is OfflinePairingFrame.Hello -> "hello"
            is OfflinePairingFrame.Signature -> "signature"
            is OfflinePairingFrame.Cancel -> "cancel"
        }
        writtenFrames += frame
        if (frame is OfflinePairingFrame.Cancel) cancelWriteCount++
    }
    fun queueInbound(frame: OfflinePairingFrame) { check(inbound.trySend(frame).isSuccess) }
    fun releaseFirstWrite() { firstWrite.complete(Unit) }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            closeCount++
            firstWrite.complete(Unit)
        }
    }
}

private class BudgetedWriteConnection(pin: ByteArray) : RuntimePairingConnection {
    private val pin = pin.copyOf()
    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    override val peerSpkiSha256: ByteArray get() = pin.copyOf()
    val writeKinds = mutableListOf<String>()
    var closeCount = 0
    override suspend fun read(): OfflinePairingFrame = kotlinx.coroutines.awaitCancellation()
    override suspend fun write(frame: OfflinePairingFrame) {
        kotlinx.coroutines.delay(PAIRING_WRITE_TIMEOUT_MILLIS)
        writeKinds += if (frame is OfflinePairingFrame.Cancel) "cancel" else "hello"
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) closeCount++
    }
}

private fun runtimeIdentity(deviceId: String, name: String, seed: Int) = OfflinePairingIdentity(
    deviceId, name,
    ByteArray(32) { (seed + it).toByte() },
    ByteArray(32) { (seed + 1 + it).toByte() },
    ByteArray(64) { (seed + 2 + it).toByte() },
    ByteArray(32) { (seed + 3 + it).toByte() },
)

private fun runtimeQr(identity: OfflinePairingIdentity, lifetimeMillis: Long) = LanPairingQr(
    1, UUID.randomUUID().toString(), 1, lifetimeMillis, identity.deviceId, identity.displayName,
    LanPairingBytes(identity.encryptionPublicKey), LanPairingBytes(identity.signingPublicKey),
    LanPairingBytes(identity.tlsSpkiSha256), LanPairingBytes(ByteArray(32) { 42 }),
)

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
    override fun cancelAuthenticator(sessionToken: ByteArray, sessionId: String): ByteArray =
        LanPairingCrypto.cancelAuthenticator(sessionToken, sessionId)
    override fun verifyCancelAuthenticator(sessionToken: ByteArray, sessionId: String, authenticator: ByteArray): Boolean =
        LanPairingCrypto.verifyCancelAuthenticator(sessionToken, sessionId, authenticator)
    override fun signTranscript(transcript: ByteArray, secretKey: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(transcript)
    override fun verifyTranscript(transcript: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean =
        signature.contentEquals(MessageDigest.getInstance("SHA-512").digest(transcript))
}
