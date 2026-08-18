package co.twinotify.core.pairing.lan

import co.twinotify.core.OfflinePairingApiError
import co.twinotify.core.OfflinePairingApiException
import co.twinotify.core.OfflinePairingApiPhase
import co.twinotify.core.OfflinePairingApiRole
import co.twinotify.core.OfflinePairingPublicStatus
import co.twinotify.core.OfflinePairingRuntime
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

/** Count- and byte-bounded mailbox used by the sole pairing runtime actor. */
internal class BoundedPairingActorMailbox<T>(
    private val maxEvents: Int,
    private val maxBytes: Int,
) : Closeable {
    private data class Entry<T>(val value: T, val bytes: Int)

    private val monitor = Any()
    private val channel = Channel<Entry<T>>(maxEvents)
    private var queuedEvents = 0
    private var queuedBytes = 0
    private var closed = false

    init {
        require(maxEvents > 0 && maxBytes > 0) { "invalid_pairing_actor_budget" }
    }

    fun trySend(value: T, bytes: Int): Boolean = synchronized(monitor) {
        if (closed || bytes <= 0 || queuedEvents >= maxEvents || bytes > maxBytes - queuedBytes) return false
        val result = channel.trySend(Entry(value, bytes))
        if (result.isSuccess) {
            queuedEvents++
            queuedBytes += bytes
            true
        } else false
    }

    suspend fun receive(): T {
        val entry = channel.receive()
        synchronized(monitor) {
            queuedEvents--
            queuedBytes -= entry.bytes
        }
        return entry.value
    }

    suspend fun receiveUnless(priority: Channel<Unit>): T? {
        if (priority.tryReceive().isSuccess) return null
        return select {
            priority.onReceive { null }
            channel.onReceive { entry ->
                synchronized(monitor) {
                    queuedEvents--
                    queuedBytes -= entry.bytes
                }
                entry.value
            }
        }
    }

    override fun close() {
        synchronized(monitor) {
            if (closed) return
            closed = true
            channel.close()
        }
    }
}

internal interface OfflinePairingSessionTransport : Closeable {
    suspend fun open(role: OfflinePairingRole, qr: LanPairingQr, onNetworkLost: () -> Unit): RuntimePairingConnection
}

private sealed interface PairingActorEvent {
    data object Open : PairingActorEvent
    data class Send(val frame: OfflinePairingFrame) : PairingActorEvent
    data class Received(val frame: OfflinePairingFrame) : PairingActorEvent
    data class Fail(val error: OfflinePairingError) : PairingActorEvent
    data object Confirm : PairingActorEvent
    data object Close : PairingActorEvent
}

/**
 * Sole owner of one provisional pairing ceremony. The coordinator sees only a
 * synchronous bounded port; this actor performs every suspend transport effect
 * and serializes all writes and callbacks off the main thread.
 */
internal class OfflinePairingRuntimeAdapter(
    scope: CoroutineScope,
    private val pairingRole: OfflinePairingRole,
    private val qr: LanPairingQr,
    private val localIdentity: OfflinePairingIdentity,
    private val committer: OfflinePairingCommitter,
    private val transport: OfflinePairingSessionTransport,
    private val nonce: ByteArray,
    override val qrJson: String?,
    private val statusSink: (OfflinePairingPublicStatus) -> Unit,
    private val crypto: OfflinePairingCrypto = ProductionOfflinePairingCrypto,
    private val monotonicMillis: () -> Long = { android.os.SystemClock.elapsedRealtime() },
    private val actorDispatcher: CoroutineDispatcher = Dispatchers.IO,
    eventCapacity: Int = DEFAULT_EVENT_CAPACITY,
    eventByteBudget: Int = DEFAULT_EVENT_BYTES,
    private val commitFence: OfflinePairingCommitFence? = null,
) : OfflinePairingRuntime {
    private val mailbox = BoundedPairingActorMailbox<PairingActorEvent>(eventCapacity, eventByteBudget)
    private val terminalCancel = Channel<Unit>(1)
    private val cancelRequested = AtomicBoolean(false)
    private val cleaned = AtomicBoolean(false)
    @Volatile private var connection: RuntimePairingConnection? = null
    private val connectionClosed = AtomicBoolean(false)
    private val forcedTerminal = AtomicReference<OfflinePairingError?>(null)
    private var reader: Job? = null
    private var deadline: Job? = null
    private var capturingTerminalCancel = false
    private var terminalCancelFrame: OfflinePairingFrame.Cancel? = null
    private val port = object : OfflinePairingPort {
        override fun monotonicMillis(): Long = this@OfflinePairingRuntimeAdapter.monotonicMillis()
        override fun advertise(sessionId: String) = enqueue(PairingActorEvent.Open, CONTROL_EVENT_BYTES)
        override fun resolve(sessionId: String, expectedTlsSpkiSha256: ByteArray) =
            enqueue(PairingActorEvent.Open, CONTROL_EVENT_BYTES)
        override fun send(frame: OfflinePairingFrame) {
            if (capturingTerminalCancel && frame is OfflinePairingFrame.Cancel) {
                check(terminalCancelFrame == null) { "duplicate_terminal_cancel" }
                terminalCancelFrame = frame
            } else enqueue(PairingActorEvent.Send(frame), frameWeight(frame))
        }
        override fun close() {
            if (!capturingTerminalCancel) enqueue(PairingActorEvent.Close, CONTROL_EVENT_BYTES)
        }
    }
    private val coordinator = OfflinePairingCoordinator(
        role = pairingRole,
        localIdentity = localIdentity,
        port = port,
        committer = object : OfflinePairingCommitter {
            override fun existingPeer(): OfflinePairingExistingPeer? = committer.existingPeer()
            override fun commit(value: OfflinePairingCommit): Boolean = committer.commit(value)
        },
        crypto = crypto,
        statusSink = ::publish,
    )

    override val role: OfflinePairingApiRole = when (pairingRole) {
        OfflinePairingRole.INITIATOR -> OfflinePairingApiRole.INITIATOR
        OfflinePairingRole.JOINER -> OfflinePairingApiRole.JOINER
    }
    override val sessionId: String = qr.sessionId
    override val job: Job = scope.launch(actorDispatcher) { runActor() }

    init {
        require(nonce.size == 32) { "invalid LAN pairing nonce" }
    }

    override fun confirm() = enqueue(PairingActorEvent.Confirm, CONTROL_EVENT_BYTES)
    override suspend fun cancel() {
        if (cancelRequested.compareAndSet(false, true) && terminalCancel.trySend(Unit).isFailure) {
            forceStop(OfflinePairingError.CANCELLED)
        }
        if (withTimeoutOrNull(CANCEL_TIMEOUT_MILLIS) { job.join(); true } != true) {
            forceStop(OfflinePairingError.CANCELLED)
            withTimeoutOrNull(CLEANUP_TIMEOUT_MILLIS) { job.join() }
        }
    }
    override fun close() {
        if (!mailbox.trySend(PairingActorEvent.Close, CONTROL_EVENT_BYTES)) job.cancel()
    }

    private suspend fun runActor() {
        try {
            coordinator.start(qr, nonce.copyOf())
            deadline = CoroutineScope(kotlin.coroutines.coroutineContext).launch(actorDispatcher) {
                delay(qr.lifetimeMillis)
                forceStop(OfflinePairingError.EXPIRED)
            }
            while (kotlin.coroutines.coroutineContext.isActive) {
                val event = mailbox.receiveUnless(terminalCancel) ?: run {
                    writeTerminalCancel()
                    return
                }
                when (event) {
                    PairingActorEvent.Open -> if (connection == null) openConnection()
                    is PairingActorEvent.Send -> connection?.write(event.frame)
                        ?: coordinator.fail(OfflinePairingError.INVALID_FRAME)
                    is PairingActorEvent.Received -> coordinator.onPeerFrame(event.frame)
                    is PairingActorEvent.Fail -> coordinator.fail(event.error)
                    PairingActorEvent.Confirm -> coordinator.confirmLocally()
                    PairingActorEvent.Close -> return
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: PairingWifiNetworkException) {
            coordinator.fail(
                if (error.failure == PairingWifiNetworkFailure.PERMISSION_DENIED) {
                    OfflinePairingError.WIFI_PERMISSION_DENIED
                } else OfflinePairingError.WIFI_UNAVAILABLE,
            )
        } catch (error: PairingTransportException) {
            coordinator.fail(error.failure.toOfflinePairingError())
        } catch (_: Throwable) {
            coordinator.fail(OfflinePairingError.INVALID_FRAME)
        } finally {
            forcedTerminal.get()?.let(coordinator::fail)
            withContext(NonCancellable) { cleanup() }
        }
    }

    private suspend fun openConnection() {
        val opened = transport.open(pairingRole, qr) {
            mailbox.trySend(PairingActorEvent.Fail(OfflinePairingError.WIFI_UNAVAILABLE), CONTROL_EVENT_BYTES)
        }
        val pin = opened.peerSpkiSha256
        if (pin == null || pin.size != 32) {
            opened.close()
            throw PairingTransportException(PairingTransportFailure.TLS_FAILED)
        }
        connection = opened
        coordinator.onTlsAuthenticated(pin)
        reader = CoroutineScope(kotlin.coroutines.coroutineContext).launch(actorDispatcher) {
            try {
                while (isActive) {
                    val frame = opened.read()
                    if (!mailbox.trySend(PairingActorEvent.Received(frame), frameWeight(frame))) {
                        closeConnection()
                        mailbox.trySend(PairingActorEvent.Fail(OfflinePairingError.INVALID_FRAME), CONTROL_EVENT_BYTES)
                        return@launch
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mailbox.trySend(PairingActorEvent.Fail(OfflinePairingError.INVALID_FRAME), CONTROL_EVENT_BYTES)
            }
        }
    }

    private fun publish(status: OfflinePairingStatus) {
        statusSink(
            OfflinePairingPublicStatus(
                role = role,
                phase = status.state.toApiPhase(),
                sessionId = sessionId,
                error = status.error?.toApiError(),
                peerDisplayName = status.peerDisplayName,
                sas = status.sas,
                completed = status.state == OfflinePairingState.COMPLETE,
            ),
        )
    }

    private suspend fun writeTerminalCancel() {
        capturingTerminalCancel = true
        try {
            coordinator.cancel()
            terminalCancelFrame?.let { frame -> connection?.write(frame) }
        } finally {
            terminalCancelFrame = null
            capturingTerminalCancel = false
        }
    }

    private suspend fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        commitFence?.close()
        deadline?.cancelAndJoin()
        reader?.cancelAndJoin()
        closeConnection()
        runCatching { transport.close() }
        terminalCancel.close()
        mailbox.close()
    }

    private fun enqueue(event: PairingActorEvent, bytes: Int, start: Boolean = false) {
        if (!mailbox.trySend(event, bytes)) {
            if (!start) throw OfflinePairingApiException(OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
            throw IllegalStateException("pairing_actor_unavailable")
        }
    }

    private suspend fun forceStop(error: OfflinePairingError) {
        forcedTerminal.compareAndSet(null, error)
        commitFence?.close()
        closeConnection()
        job.cancel(CancellationException(error.code))
    }

    private fun closeConnection() {
        if (connectionClosed.compareAndSet(false, true)) runCatching { connection?.close() }
    }

    private fun frameWeight(frame: OfflinePairingFrame): Int = when (frame) {
        is OfflinePairingFrame.Hello -> 512
        is OfflinePairingFrame.Signature -> 256
        is OfflinePairingFrame.Cancel -> 64
    }

    private companion object {
        const val CONTROL_EVENT_BYTES = 32
        const val DEFAULT_EVENT_CAPACITY = 16
        const val DEFAULT_EVENT_BYTES = 16 * 1024
        const val CLEANUP_TIMEOUT_MILLIS = 1_000L
        const val CANCEL_TIMEOUT_MILLIS = PAIRING_WRITE_TIMEOUT_MILLIS * 2 + CLEANUP_TIMEOUT_MILLIS * 2
    }
}

internal fun OfflinePairingState.toApiPhase(): OfflinePairingApiPhase = OfflinePairingApiPhase.valueOf(name)

internal fun PairingTransportFailure.toOfflinePairingError(): OfflinePairingError = when (this) {
    PairingTransportFailure.TLS_PIN_MISMATCH -> OfflinePairingError.TLS_PIN_MISMATCH
    PairingTransportFailure.CONNECT_TIMEOUT,
    PairingTransportFailure.ACCEPT_TIMEOUT,
    PairingTransportFailure.READ_TIMEOUT,
    PairingTransportFailure.NSD_FAILED -> OfflinePairingError.WIFI_UNAVAILABLE
    PairingTransportFailure.PERMISSION_DENIED -> OfflinePairingError.WIFI_PERMISSION_DENIED
    PairingTransportFailure.INVALID_FRAME,
    PairingTransportFailure.FRAME_BUDGET_EXCEEDED,
    PairingTransportFailure.TLS_FAILED -> OfflinePairingError.INVALID_FRAME
}

internal fun OfflinePairingError.toApiError(): OfflinePairingApiError = when (this) {
    OfflinePairingError.EXPIRED -> OfflinePairingApiError.EXPIRED
    OfflinePairingError.TLS_PIN_MISMATCH -> OfflinePairingApiError.TLS_PIN_MISMATCH
    OfflinePairingError.IDENTITY_MISMATCH -> OfflinePairingApiError.IDENTITY_MISMATCH
    OfflinePairingError.INVALID_FRAME -> OfflinePairingApiError.INVALID_FRAME
    OfflinePairingError.COMMIT_FAILED -> OfflinePairingApiError.COMMIT_FAILED
    OfflinePairingError.CANCELLED -> OfflinePairingApiError.CANCELLED
    OfflinePairingError.PEER_REJECTED -> OfflinePairingApiError.PEER_REJECTED
    OfflinePairingError.WIFI_PERMISSION_DENIED -> OfflinePairingApiError.WIFI_PERMISSION_DENIED
    OfflinePairingError.WIFI_UNAVAILABLE -> OfflinePairingApiError.WIFI_UNAVAILABLE
}

internal fun PairingWifiNetworkException.toApiError(): OfflinePairingApiError = when (failure) {
    PairingWifiNetworkFailure.PERMISSION_DENIED -> OfflinePairingApiError.WIFI_PERMISSION_DENIED
    PairingWifiNetworkFailure.UNAVAILABLE -> OfflinePairingApiError.WIFI_UNAVAILABLE
}
