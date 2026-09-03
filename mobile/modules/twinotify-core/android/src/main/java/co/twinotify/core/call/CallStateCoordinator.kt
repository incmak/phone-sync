package co.twinotify.core.call

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class CallCoordinatorDebugState(
    val registered: Boolean,
    val sessionId: String?,
    val pendingCount: Int,
)

private class RebasedControlFreeState(val event: CallStateEvent) : Exception()

/** Serializes framework callbacks into privacy-bounded, strictly increasing call sessions. */
class CallStateCoordinator(
    private val source: CallStateSource,
    private val emit: suspend (CallStateEvent) -> Unit,
    private val sessionIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val controlIdFactory: () -> String = { UUID.randomUUID().toString() },
    dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val callbackMutex = Mutex()
    private val quiesceMutex = Mutex()
    private var registration: AutoCloseable? = null
    private var registrationQuiesced = false
    private var sessionId: String? = null
    private var lastFrameworkState: CallFrameworkState? = null
    private var direction = CallDirection.UNKNOWN
    private var sequence = 0L
    private var controlSourceKey: String? = null
    /**
     * Events awaiting durable custody. Framework callbacks can arrive while the first event is
     * retrying, so retain them in sequence order instead of dropping the newer state.
     */
    private val pendingEmits = ArrayDeque<CallStateEvent>()
    private var retryJob: Job? = null
    private val _status = AtomicReference(CallCaptureStatus(enabled = false, reason = CallCaptureDisabledReason.DISABLED))
    val status: CallCaptureStatus get() = _status.get()

    fun start(): CallCaptureStatus {
        synchronized(lock) {
            if (registration != null) return status
            val capabilities = source.capabilities()
            if (!capabilities.supported) {
                return disabled(CallCaptureDisabledReason.UNSUPPORTED_TELEPHONY)
            }
            if (!capabilities.permissionGranted) {
                return disabled(CallCaptureDisabledReason.PERMISSION_DENIED)
            }
            return try {
                registration = source.register { state ->
                    scope.launch { callbackMutex.withLock { onFrameworkState(state) } }
                }
                registrationQuiesced = false
                _status.set(CallCaptureStatus(enabled = true))
                status
            } catch (error: Throwable) {
                disabled(CallCaptureDisabledReason.CALLBACK_REGISTRATION_FAILED)
            }
        }
    }

    /** Debug-only host seam; bypasses telephony registration while preserving the real reducer,
     * sequence, persistence, and retry path. The caller must already be authenticated. */
    fun startForDebug(): CallCaptureStatus {
        synchronized(lock) {
            if (registration != null) return status
            registration = AutoCloseable { }
            registrationQuiesced = false
            _status.set(CallCaptureStatus(enabled = true))
            return status
        }
    }

    suspend fun injectDebugState(frameworkState: CallFrameworkState): CallStateEvent? =
        callbackMutex.withLock { onFrameworkState(frameworkState) }

    /** Publishes a fresh one-use capability generation for the current incoming call. */
    suspend fun <T> refreshControls(
        sourceKey: String,
        handles: Map<CallControlKind, T>,
    ): CallStateEvent? = callbackMutex.withLock {
        if (!_status.get().enabled || sourceKey.isEmpty()) return@withLock null
        drainPending()
        val event = synchronized(lock) {
            val currentSession = sessionId ?: return@synchronized null
            val frameworkState = lastFrameworkState ?: return@synchronized null
            val requiredKinds = legalControlKinds(frameworkState, direction) ?: return@synchronized null
            if (handles.keys != requiredKinds) return@synchronized null
            sequence += 1L
            val controls = requiredKinds.map { kind ->
                CallControlDescriptor(canonicalUuid(controlIdFactory(), "call control factory"), kind)
            }
            require(controls.map { it.controlId }.distinct().size == controls.size) {
                "call control factory must return a fresh UUID per kind"
            }
            val generation = CallCapabilityGeneration(
                sequence = sequence,
                sourceKey = sourceKey,
                controls = controls.associate { descriptor ->
                    descriptor.controlId to RegisteredCallControl(
                        descriptor.kind,
                        requireNotNull(handles[descriptor.kind]),
                    )
                },
            )
            controlSourceKey = sourceKey
            CallStateEvent(
                callSessionId = currentSession,
                state = frameworkState.wireState(),
                direction = direction,
                sequence = sequence,
                controls = controls,
                pendingGeneration = generation,
            )
        } ?: return@withLock null
        publish(event)
        event
    }

    /** Emits a newer control-free sequence only when a generation is currently advertised. */
    suspend fun clearControls(): CallStateEvent? = callbackMutex.withLock {
        drainPending()
        val event = synchronized(lock) {
            val currentSession = sessionId ?: return@synchronized null
            val frameworkState = lastFrameworkState ?: return@synchronized null
            if (controlSourceKey == null) return@synchronized null
            controlSourceKey = null
            sequence += 1L
            CallStateEvent(
                callSessionId = currentSession,
                state = frameworkState.wireState(),
                direction = direction,
                sequence = sequence,
            )
        } ?: return@withLock null
        publish(event)
        event
    }

    internal fun currentCallSnapshot(): CallControlSessionSnapshot? = synchronized(lock) {
        val state = lastFrameworkState ?: return@synchronized null
        if (sessionId == null || state == CallFrameworkState.IDLE) return@synchronized null
        CallControlSessionSnapshot(state, direction)
    }

    internal fun debugState(): CallCoordinatorDebugState = synchronized(lock) {
        CallCoordinatorDebugState(
            registered = registration != null && !registrationQuiesced,
            sessionId = sessionId,
            pendingCount = pendingEmits.size,
        )
    }

    suspend fun quiesceAndTerminalize(terminalizeCommittedCalls: suspend () -> Unit) {
        quiesceMutex.withLock {
            val handle = synchronized(lock) {
                _status.set(CallCaptureStatus(false, CallCaptureDisabledReason.DISABLED))
                if (registrationQuiesced) null else registration
            }
            handle?.close()
            synchronized(lock) {
                if (registration === handle || handle == null) registrationQuiesced = true
            }

            val activeRetry = synchronized(lock) {
                retryJob.also { retryJob = null }
            }
            activeRetry?.cancelAndJoin()

            callbackMutex.withLock {
                val callbackRetry = synchronized(lock) {
                    retryJob.also { retryJob = null }
                }
                callbackRetry?.cancelAndJoin()

                if (!drainPending(scheduleRetryOnFailure = false)) {
                    throw ActiveCallRecoveryException("call_shutdown_failed")
                }
                terminalizeCommittedCalls()
                synchronized(lock) {
                    registration = null
                    registrationQuiesced = false
                    sessionId = null
                    lastFrameworkState = null
                    direction = CallDirection.UNKNOWN
                    sequence = 0L
                    controlSourceKey = null
                    pendingEmits.clear()
                }
            }
        }
    }

    fun stop() {
        val handle = synchronized(lock) {
            val current = if (registrationQuiesced) null else registration
            registration = null
            registrationQuiesced = false
            sessionId = null
            lastFrameworkState = null
            direction = CallDirection.UNKNOWN
            sequence = 0L
            controlSourceKey = null
            pendingEmits.clear()
            retryJob?.cancel()
            retryJob = null
            _status.set(CallCaptureStatus(enabled = false, reason = CallCaptureDisabledReason.DISABLED))
            current
        }
        runCatching { handle?.close() }
    }

    fun close() {
        stop()
        scope.cancel()
    }

    private suspend fun onFrameworkState(frameworkState: CallFrameworkState): CallStateEvent? {
        if (!_status.get().enabled) return null
        drainPending()
        val event = synchronized(lock) {
            if (!_status.get().enabled) return null
            if (frameworkState == lastFrameworkState && frameworkState != CallFrameworkState.IDLE) return null
            if (frameworkState == CallFrameworkState.IDLE && sessionId == null) return null
            if (frameworkState == CallFrameworkState.IDLE && lastFrameworkState == CallFrameworkState.IDLE) return null
            if (sessionId == null) {
                sessionId = sessionIdFactory().also {
                    require(UUID.fromString(it).toString().equals(it, ignoreCase = true)) {
                        "call session factory must return a UUID"
                    }
                }
                sequence = 0L
            }
            if (frameworkState == CallFrameworkState.RINGING) {
                direction = CallDirection.INCOMING
            }
            // A framework transition invalidates every handle from the prior state. The emitted
            // organic state is deliberately control-free until the listener refreshes it.
            controlSourceKey = null
            lastFrameworkState = frameworkState
            sequence += 1L
            val event = CallStateEvent(
                callSessionId = requireNotNull(sessionId),
                state = frameworkState.wireState(),
                direction = direction,
                sequence = sequence,
            )
            if (frameworkState == CallFrameworkState.IDLE) {
                sessionId = null
                lastFrameworkState = null
                direction = CallDirection.UNKNOWN
                sequence = 0L
                controlSourceKey = null
            }
            event
        }
        publish(event)
        return event
    }

    private suspend fun publish(event: CallStateEvent) {
        val queued = synchronized(lock) { pendingEmits.isNotEmpty() }
        if (queued) {
            enqueuePending(event)
        } else {
            try {
                if (!deliver(event)) enqueuePending(event)
            } catch (cancellation: CancellationException) {
                enqueuePending(event)
                throw cancellation
            } catch (rebased: RebasedControlFreeState) {
                enqueuePending(rebased.event)
            }
        }
    }

    private suspend fun deliver(event: CallStateEvent): Boolean {
        return try {
            emit(event)
            true
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (stale: CallStatePersistenceException) {
            val latest = stale.latestSequence
            if (stale.code != "call_state_stale" || latest == null) {
                _status.updateAndGet { current -> current.copy(lastErrorCode = "call_callback_emit_failed") }
                return false
            }
            // This exact immutable event can never win after a higher Room sequence. Drop it and
            // rebase so the listener can publish a fresh generation instead of retrying forever.
            if (event.controls.isEmpty()) {
                val nextSequence = Math.addExact(latest, 1L)
                synchronized(lock) {
                    if (sessionId == event.callSessionId) sequence = maxOf(sequence, nextSequence)
                }
                throw RebasedControlFreeState(event.copy(sequence = nextSequence))
            }
            synchronized(lock) { sequence = maxOf(sequence, latest) }
            _status.updateAndGet { current -> current.copy(lastErrorCode = "call_state_stale") }
            true
        } catch (error: Throwable) {
            _status.updateAndGet { current -> current.copy(lastErrorCode = "call_callback_emit_failed") }
            false
        }
    }

    /** Drains durable events in sequence order; the failed head remains queued for retry. */
    private suspend fun drainPending(scheduleRetryOnFailure: Boolean = true): Boolean {
        while (true) {
            val next = synchronized(lock) {
                pendingEmits.removeFirstOrNull()
            } ?: return true
            val delivered = try {
                deliver(next)
            } catch (cancellation: CancellationException) {
                synchronized(lock) { pendingEmits.addFirst(next) }
                throw cancellation
            } catch (rebased: RebasedControlFreeState) {
                synchronized(lock) { pendingEmits.addFirst(rebased.event) }
                if (scheduleRetryOnFailure) scheduleRetry()
                return false
            }
            if (!delivered) {
                synchronized(lock) { pendingEmits.addFirst(next) }
                if (scheduleRetryOnFailure) scheduleRetry()
                return false
            }
        }
    }

    private fun enqueuePending(event: CallStateEvent) {
        synchronized(lock) {
            if (pendingEmits.size >= MAX_PENDING_EVENTS) {
                // Keep the queue bounded under a broken sink. Preserve the oldest terminal idle
                // barrier before retaining the newest state; intermediate callbacks are explicitly
                // latest-state coalesced only after the safety bound is reached.
                val retainedIdle = pendingEmits.firstOrNull { it.state == "idle" }
                pendingEmits.clear()
                if (retainedIdle != null) pendingEmits.addLast(retainedIdle)
            }
            pendingEmits.addLast(event)
        }
        scheduleRetry()
    }

    private fun scheduleRetry() {
        synchronized(lock) {
            if (!_status.get().enabled) return
            if (retryJob?.isActive == true) return
            retryJob = scope.launch {
                kotlinx.coroutines.delay(RETRY_DELAY_MS)
                callbackMutex.withLock {
                    val hasPending = synchronized(lock) { pendingEmits.isNotEmpty() }
                    if (!hasPending) {
                        synchronized(lock) { retryJob = null }
                        return@withLock
                    }
                    if (drainPending()) {
                        synchronized(lock) { retryJob = null }
                    } else {
                        synchronized(lock) { retryJob = null }
                        scheduleRetry()
                    }
                }
            }
        }
    }

    private fun disabled(reason: CallCaptureDisabledReason): CallCaptureStatus {
        val value = CallCaptureStatus(enabled = false, reason = reason, lastErrorCode = reason.code)
        _status.set(value)
        return value
    }

    private companion object {
        const val RETRY_DELAY_MS = 250L
        const val MAX_PENDING_EVENTS = 64
    }
}

private fun CallFrameworkState.wireState(): String = when (this) {
    CallFrameworkState.RINGING -> "ringing"
    CallFrameworkState.OFFHOOK -> "active"
    CallFrameworkState.IDLE -> "idle"
}

private fun legalControlKinds(
    state: CallFrameworkState,
    direction: CallDirection,
): LinkedHashSet<CallControlKind>? = when {
    state == CallFrameworkState.RINGING && direction == CallDirection.INCOMING ->
        linkedSetOf(CallControlKind.ANSWER, CallControlKind.DECLINE)
    state == CallFrameworkState.OFFHOOK && direction == CallDirection.INCOMING ->
        linkedSetOf(CallControlKind.HANG_UP)
    else -> null
}

private fun canonicalUuid(value: String, source: String): String {
    require(UUID.fromString(value).toString() == value) { "$source must return a lower-case canonical UUID" }
    return value
}
