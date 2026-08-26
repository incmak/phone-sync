package co.twinotify.core.pairing

import android.content.Context
import android.content.pm.ApplicationInfo
import co.twinotify.core.auth.JwtMinter
import co.twinotify.core.crypto.CryptoStore
import co.twinotify.core.listener.DurableCapturePersister
import co.twinotify.core.service.ServiceConfigStore
import co.twinotify.core.service.SyncService
import co.twinotify.core.service.SyncServiceStatus
import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.PreparedLocalUnpairService
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.PeerStore
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob

internal enum class LocalUnpairCustodyOutcome(val statusCode: String) {
    LAN("lan"),
    RELAY("relay"),
    TIMEOUT("timeout"),
    UNAVAILABLE("unavailable"),
    DELIVERY_FAILED("delivery_failed"),
    NO_PEER("no_peer"),
}

internal data class LocalUnpairResult(
    val msgId: String?,
    val custody: LocalUnpairCustodyOutcome,
)

/** Process-local, bounded observation for diagnostics and the authenticated debug provider. */
internal object LocalUnpairStatus {
    private val _lastOutcome = MutableStateFlow<String?>(null)
    val lastOutcome: StateFlow<String?> = _lastOutcome.asStateFlow()

    fun record(outcome: LocalUnpairCustodyOutcome) {
        _lastOutcome.value = outcome.statusCode
    }

    fun record(result: LocalUnpairResult) {
        record(result.custody)
    }
}

internal class LocalUnpairCoordinator(
    private val prepare: suspend () -> PreparedLocalUnpairService,
    private val persistUnpair: suspend (String) -> String,
    private val revokePeer: suspend () -> Unit,
    private val wipeLocal: suspend () -> Unit,
    private val newMessageId: () -> String = { UUID.randomUUID().toString() },
    private val custodyTimeoutMillis: Long = 5_000L,
    private val onCustodyOutcome: (LocalUnpairCustodyOutcome) -> Unit = {},
) {
    suspend fun execute(): LocalUnpairResult {
        val msgId = newMessageId()
        val prepared = prepare()
        val reservation = prepared.reserveCustody(msgId)
        val outcome = try {
            val persistedId = persistUnpair(msgId)
            check(persistedId == msgId) { "unpair persistence returned a different message ID" }
            if (!prepared.transportAvailable || reservation == null) {
                LocalUnpairCustodyOutcome.UNAVAILABLE
            } else {
                try {
                    when (reservation.await(custodyTimeoutMillis)) {
                        CustodyRoute.LAN -> LocalUnpairCustodyOutcome.LAN
                        CustodyRoute.RELAY -> LocalUnpairCustodyOutcome.RELAY
                        null -> LocalUnpairCustodyOutcome.TIMEOUT
                    }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    LocalUnpairCustodyOutcome.DELIVERY_FAILED
                }
            }
        } catch (cancellation: CancellationException) {
            reservation?.close()
            throw cancellation
        } catch (_: Exception) {
            reservation?.close()
            LocalUnpairCustodyOutcome.DELIVERY_FAILED
        }
        try {
            onCustodyOutcome(outcome)
        } catch (_: Exception) {
            // Outcome reporting is advisory. It cannot hold the security teardown boundary open.
        }
        prepared.quiesceAndAwait()
        revokePeer()
        withContext(NonCancellable) { wipeLocal() }
        return LocalUnpairResult(msgId, outcome)
    }
}

internal class LocalUnpairRequestGate {
    private class InFlight(
        val deferred: Deferred<LocalUnpairResult>,
        var waiters: Int,
    )

    private var active: InFlight? = null

    @Synchronized
    fun start(
        scope: CoroutineScope,
        request: suspend () -> LocalUnpairResult,
    ): LocalUnpairRequest {
        active?.takeIf { !it.deferred.isCompleted }?.let { shared ->
            shared.waiters += 1
            return waiterFor(shared)
        }
        val next = scope.async(start = CoroutineStart.LAZY) { request() }
        val shared = InFlight(next, waiters = 1)
        active = shared
        next.invokeOnCompletion {
            synchronized(this) {
                if (active === shared) active = null
            }
        }
        next.start()
        return waiterFor(shared)
    }

    private fun waiterFor(shared: InFlight) = LocalUnpairRequest(shared.deferred) { cancellation ->
        synchronized(this) {
            if (active !== shared) return@synchronized
            check(shared.waiters > 0) { "local unpair waiter count underflow" }
            shared.waiters -= 1
            if (shared.waiters == 0 && cancellation != null && !shared.deferred.isCompleted) {
                shared.deferred.cancel(cancellation)
            }
        }
    }
}

/** One process-wide admission gate shared by every production local-unpair caller. */
internal class SharedLocalUnpairEntryPoint(
    private val executionScope: CoroutineScope,
) {
    private val gate = LocalUnpairRequestGate()

    suspend fun start(
        quiesceOfflinePairing: suspend () -> Unit,
        execute: suspend () -> LocalUnpairResult,
    ): LocalUnpairRequest {
        quiesceOfflinePairing()
        return gate.start(executionScope, execute)
    }
}

/** The sole production orchestration for UI and authenticated debug local-unpair requests. */
internal object ProductionLocalUnpairEntryPoint {
    private val shared = SharedLocalUnpairEntryPoint(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    suspend fun start(
        context: Context,
        quiesceOfflinePairing: suspend () -> Unit,
    ): LocalUnpairRequest = shared.start(quiesceOfflinePairing) {
        execute(context.applicationContext)
    }

    private suspend fun execute(context: Context): LocalUnpairResult {
        val peer = PeerStore.load(context) ?: return LocalUnpairResult(
            msgId = null,
            custody = LocalUnpairCustodyOutcome.NO_PEER,
        )
        val config = ServiceConfigStore.read(context)
        val revocationDecision = UnpairRevocationPolicy.decide(
            peerPresent = true,
            relayRevocationRequired = peer.relayRevocationRequired,
            lanBindingId = peer.lanBindingId,
            relayUrl = config.relayUrl,
        )
        return LocalUnpairCoordinator(
            prepare = { SyncService.prepareLocalUnpair(context) },
            persistUnpair = { msgId ->
                DurableCapturePersister(context).persistUnpair(
                    reason = "local_user",
                    originDevice = DeviceIdentity.getOrCreate(context),
                    timestamp = System.currentTimeMillis(),
                    msgId = msgId,
                )
            },
            revokePeer = {
                UnpairRevocationExecutor.execute(
                    decision = revocationDecision,
                    markRevocationIntent = {
                        ServiceConfigStore.setRevocationRequestedAt(context).revocationRequestedAt != null
                    },
                    revoke = { relayUrl, markerPresent ->
                        val (_, sign) = CryptoStore.loadOrGenerate(context)
                        PairProtocol.revoke(
                            relayUrl,
                            JwtMinter.mint(DeviceIdentity.getOrCreate(context), sign.secretKey),
                            debug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0,
                            revocationMarkerPresent = markerPresent,
                        )
                    },
                )
            },
            wipeLocal = {
                UnpairOps.wipeAll(context)
                SyncServiceStatus.notifyPeerUnpaired()
            },
            onCustodyOutcome = LocalUnpairStatus::record,
        ).execute()
    }
}

internal class LocalUnpairRequest(
    private val deferred: Deferred<LocalUnpairResult>,
    private val release: (CancellationException?) -> Unit,
) {
    private val released = AtomicBoolean(false)

    internal fun sharesExecutionWith(other: LocalUnpairRequest): Boolean =
        deferred === other.deferred

    @OptIn(InternalCoroutinesApi::class)
    suspend fun await(): LocalUnpairResult = try {
        deferred.await()
    } catch (cancellation: CancellationException) {
        if (!currentCoroutineContext().isActive) {
            val callerCancellation = currentCoroutineContext()[Job]
                ?.getCancellationException()
                ?: cancellation
            releaseOnce(callerCancellation)
            throw callerCancellation
        }
        throw cancellation
    } finally {
        releaseOnce(null)
    }

    private fun releaseOnce(cancellation: CancellationException?) {
        if (released.compareAndSet(false, true)) release(cancellation)
    }
}

internal suspend fun awaitLocalUnpairResult(
    request: LocalUnpairRequest,
): LocalUnpairResult = request.await()

internal suspend fun awaitLocalUnpairResultAndRecord(
    request: LocalUnpairRequest,
): LocalUnpairResult = awaitLocalUnpairResult(request).also(LocalUnpairStatus::record)
