package co.twinotify.core.call

import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.ReliableDeliveryDao
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val CALL_RECOVERY_RETRY_DELAY_MS = 1_000L
private const val CALL_RECOVERY_FAILED_CODE = "call_recovery_failed"

/**
 * Reconciles persisted local call state before registering a live call source.
 *
 * The capture callback is deliberately invoked after recovery even when it
 * represents disabled capture: recovery owns durable state convergence, while
 * the callback owns the current runtime opt-in decision.
 */
internal suspend fun recoverCallsBeforeCapture(
    recover: suspend () -> Unit,
    startCapture: suspend () -> Unit,
    reportFailure: (String) -> Unit,
) {
    while (currentCoroutineContext().isActive) {
        try {
            recover()
            startCapture()
            return
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            try {
                reportFailure(recoveryFailureCode(failure))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // Health reporting is advisory. It must not prevent recovery.
            }
            delay(CALL_RECOVERY_RETRY_DELAY_MS)
        }
    }
}

private fun recoveryFailureCode(failure: Exception): String = when (
    (failure as? ActiveCallRecoveryException)?.code
) {
    "call_recovery_failed",
    "call_recovery_invalid_canonical",
    "call_recovery_stale",
    -> (failure as ActiveCallRecoveryException).code
    else -> CALL_RECOVERY_FAILED_CODE
}

/**
 * Installs a normal (non-debug) coordinator and converts only framework
 * registration failure into the bounded recovery path. The failed coordinator
 * is always closed and removed before either health reporting or throwing.
 */
internal fun startNormalCallCapture(
    coordinator: CallStateCoordinator,
    install: (CallStateCoordinator?) -> Unit,
    reportRegistrationFailure: (String) -> Unit,
): CallCaptureStatus {
    install(coordinator)
    val status = coordinator.start()
    if (!status.enabled && status.reason == CallCaptureDisabledReason.CALLBACK_REGISTRATION_FAILED) {
        coordinator.close()
        install(null)
        reportRegistrationFailure(CallCaptureDisabledReason.CALLBACK_REGISTRATION_FAILED.code)
        throw ActiveCallRecoveryException(CALL_RECOVERY_FAILED_CODE)
    }
    return status
}

/** Keeps at most one active recovery-and-capture startup sequence. */
internal class CallCaptureStartupGate {
    private var job: Job? = null

    @Synchronized
    fun start(
        scope: CoroutineScope,
        recover: suspend () -> Unit,
        startCapture: suspend () -> Unit,
        reportFailure: (String) -> Unit,
    ): Job {
        job?.takeIf { it.isActive }?.let { return it }
        val nextJob = scope.launch(start = CoroutineStart.LAZY) {
            recoverCallsBeforeCapture(recover, startCapture, reportFailure)
        }
        job = nextJob
        nextJob.invokeOnCompletion {
            synchronized(this) {
                if (job === nextJob) job = null
            }
        }
        nextJob.start()
        return nextJob
    }
}

internal interface ActiveCallRecoveryStore {
    suspend fun activeLocalCalls(originDevice: String): List<CanonicalNotificationState>
    suspend fun canonical(canonId: String): CanonicalNotificationState?
    suspend fun nextSequence(canonId: String): Long
}

internal class DaoActiveCallRecoveryStore(
    private val dao: ReliableDeliveryDao,
) : ActiveCallRecoveryStore {
    override suspend fun activeLocalCalls(originDevice: String): List<CanonicalNotificationState> =
        dao.activeLocalCallStates(originDevice)

    override suspend fun canonical(canonId: String): CanonicalNotificationState? = dao.canonical(canonId)

    override suspend fun nextSequence(canonId: String): Long = dao.nextCaptureSequenceForEvent(canonId)
}

internal data class ActiveCallRecoverySummary(val terminated: Int)

internal class ActiveCallRecoveryException(
    val code: String,
    cause: Exception? = null,
) : Exception(code, cause)

internal class ActiveCallTerminalizer(
    private val store: ActiveCallRecoveryStore,
    private val persister: CallStatePersister,
) {
    suspend fun recover(originDevice: String): ActiveCallRecoverySummary = try {
        var terminated = 0
        for (row in store.activeLocalCalls(originDevice)) {
            terminate(row, originDevice)
            terminated += 1
        }
        ActiveCallRecoverySummary(terminated)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (recovery: ActiveCallRecoveryException) {
        throw recovery
    } catch (_: Exception) {
        throw ActiveCallRecoveryException(CODE_FAILED)
    }

    private suspend fun terminate(row: CanonicalNotificationState, originDevice: String) {
        val sessionId = sessionId(row.canonId)
        val direction = direction(row.desiredPayloadJson)
        repeat(MAX_ATTEMPTS) { attempt ->
            val event = CallStateEvent(
                callSessionId = sessionId,
                state = "idle",
                direction = direction,
                sequence = store.nextSequence(row.canonId),
            )
            when (persister.persistForRecovery(event, originDevice)) {
                is CallStatePersistResult.Persisted,
                is CallStatePersistResult.Duplicate,
                CallStatePersistResult.OwnershipLost -> return
                is CallStatePersistResult.Stale -> {
                    val current = store.canonical(row.canonId)
                    if (current?.originDevice != originDevice || current.state != ACTIVE) return
                    if (attempt == MAX_ATTEMPTS - 1) throw ActiveCallRecoveryException(CODE_STALE)
                }
            }
        }
    }

    private fun sessionId(canonId: String): String {
        val value = canonId.removePrefix(CALL_PREFIX)
        val isCanonicalLowercaseUuid = try {
            UUID.fromString(value).toString() == value
        } catch (_: IllegalArgumentException) {
            false
        }
        if (!canonId.startsWith(CALL_PREFIX) || !isCanonicalLowercaseUuid) {
            throw ActiveCallRecoveryException(CODE_INVALID_CANONICAL)
        }
        return value
    }

    private fun direction(payload: String?): CallDirection {
        if (payload == null || payload.length > MAX_DIRECTION_JSON_CHARS) return CallDirection.UNKNOWN
        val value = try {
            JSONObject(payload).opt("direction") as? String
        } catch (_: Exception) {
            null
        }
        return when (value) {
            "incoming" -> CallDirection.INCOMING
            "outgoing" -> CallDirection.OUTGOING
            "unknown" -> CallDirection.UNKNOWN
            else -> CallDirection.UNKNOWN
        }
    }

    private companion object {
        const val ACTIVE = "ACTIVE"
        const val CALL_PREFIX = "call:"
        const val CODE_FAILED = "call_recovery_failed"
        const val CODE_INVALID_CANONICAL = "call_recovery_invalid_canonical"
        const val CODE_STALE = "call_recovery_stale"
        const val MAX_ATTEMPTS = 3
        const val MAX_DIRECTION_JSON_CHARS = 1_024
    }
}
