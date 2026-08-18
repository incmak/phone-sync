package co.twinotify.core.pairing.lan

import co.twinotify.core.OfflinePairingApiError
import co.twinotify.core.OfflinePairingApiException
import co.twinotify.core.OfflinePairingApiPhase
import co.twinotify.core.OfflinePairingApiRole
import co.twinotify.core.OfflinePairingPublicStatus
import co.twinotify.core.OfflinePairingRuntime
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    data object Cancel : PairingActorEvent
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
) : OfflinePairingRuntime {
    private val mailbox = BoundedPairingActorMailbox<PairingActorEvent>(eventCapacity, eventByteBudget)
    private val cleaned = AtomicBoolean(false)
    private var connection: RuntimePairingConnection? = null
    private var reader: Job? = null
    private var deadline: Job? = null
    private val port = object : OfflinePairingPort {
        override fun monotonicMillis(): Long = this@OfflinePairingRuntimeAdapter.monotonicMillis()
        override fun advertise(sessionId: String) = enqueue(PairingActorEvent.Open, CONTROL_EVENT_BYTES)
        override fun resolve(sessionId: String, expectedTlsSpkiSha256: ByteArray) =
            enqueue(PairingActorEvent.Open, CONTROL_EVENT_BYTES)
        override fun send(frame: OfflinePairingFrame) = enqueue(PairingActorEvent.Send(frame), frameWeight(frame))
        override fun close() = enqueue(PairingActorEvent.Close, CONTROL_EVENT_BYTES)
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
    override fun cancel() = enqueue(PairingActorEvent.Cancel, CONTROL_EVENT_BYTES)
    override fun close() {
        if (!mailbox.trySend(PairingActorEvent.Close, CONTROL_EVENT_BYTES)) job.cancel()
    }

    private suspend fun runActor() {
        try {
            coordinator.start(qr, nonce.copyOf())
            deadline = CoroutineScope(kotlin.coroutines.coroutineContext).launch(actorDispatcher) {
                delay(qr.lifetimeMillis)
                mailbox.trySend(PairingActorEvent.Fail(OfflinePairingError.EXPIRED), CONTROL_EVENT_BYTES)
            }
            while (kotlin.coroutines.coroutineContext.isActive) {
                when (val event = mailbox.receive()) {
                    PairingActorEvent.Open -> if (connection == null) openConnection()
                    is PairingActorEvent.Send -> connection?.write(event.frame)
                        ?: coordinator.fail(OfflinePairingError.INVALID_FRAME)
                    is PairingActorEvent.Received -> coordinator.onPeerFrame(event.frame)
                    is PairingActorEvent.Fail -> coordinator.fail(event.error)
                    PairingActorEvent.Confirm -> coordinator.confirmLocally()
                    PairingActorEvent.Cancel -> coordinator.cancel()
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
            coordinator.fail(
                when (error.failure) {
                    PairingTransportFailure.TLS_PIN_MISMATCH -> OfflinePairingError.IDENTITY_MISMATCH
                    PairingTransportFailure.CONNECT_TIMEOUT,
                    PairingTransportFailure.ACCEPT_TIMEOUT,
                    PairingTransportFailure.READ_TIMEOUT,
                    PairingTransportFailure.NSD_FAILED -> OfflinePairingError.WIFI_UNAVAILABLE
                    else -> OfflinePairingError.INVALID_FRAME
                },
            )
        } catch (_: Throwable) {
            coordinator.fail(OfflinePairingError.INVALID_FRAME)
        } finally {
            cleanup()
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
                        opened.close()
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

    private suspend fun cleanup() {
        if (!cleaned.compareAndSet(false, true)) return
        deadline?.cancelAndJoin()
        reader?.cancelAndJoin()
        runCatching { connection?.close() }
        runCatching { transport.close() }
        mailbox.close()
    }

    private fun enqueue(event: PairingActorEvent, bytes: Int, start: Boolean = false) {
        if (!mailbox.trySend(event, bytes)) {
            if (!start) throw OfflinePairingApiException(OfflinePairingApiError.PAIR_RUNTIME_UNAVAILABLE)
            throw IllegalStateException("pairing_actor_unavailable")
        }
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
    }
}

internal fun OfflinePairingState.toApiPhase(): OfflinePairingApiPhase = OfflinePairingApiPhase.valueOf(name)

internal fun OfflinePairingError.toApiError(): OfflinePairingApiError = OfflinePairingApiError.valueOf(name)

internal fun PairingWifiNetworkException.toApiError(): OfflinePairingApiError = when (failure) {
    PairingWifiNetworkFailure.PERMISSION_DENIED -> OfflinePairingApiError.WIFI_PERMISSION_DENIED
    PairingWifiNetworkFailure.UNAVAILABLE -> OfflinePairingApiError.WIFI_UNAVAILABLE
}
