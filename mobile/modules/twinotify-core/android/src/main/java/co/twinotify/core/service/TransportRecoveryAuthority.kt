package co.twinotify.core.service

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import co.twinotify.core.storage.PeerStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class RecoveryTrigger {
    APP_FOREGROUND,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    USER_RETRY,
}

enum class RecoveryIssue(val code: String) {
    NOTIFICATION_ACCESS_REQUIRED("notification_access_required"),
    POST_NOTIFICATIONS_REQUIRED("post_notifications_required"),
    BACKGROUND_START_DENIED("background_start_denied");

    companion object {
        fun fromCode(code: String): RecoveryIssue? = entries.firstOrNull { it.code == code }
    }
}

internal data class RecoveryInputs(
    val persisted: ServiceConfig,
    val paired: Boolean,
    val lanBound: Boolean,
    val listenerPermission: Boolean,
    val postPermission: Boolean,
    val serviceActive: Boolean,
)

internal sealed interface RecoveryDecision {
    data class Start(val service: ServiceStartDecision.Start) : RecoveryDecision
    data object AlreadyRunning : RecoveryDecision
    data class NoAction(val reason: String) : RecoveryDecision
    data class Blocked(val issue: RecoveryIssue) : RecoveryDecision
}

internal object RecoveryPolicy {
    fun decide(inputs: RecoveryInputs): RecoveryDecision {
        val configured = ServiceStartPolicy.decide(
            intentAction = null,
            persisted = inputs.persisted,
            paired = inputs.paired,
            lanBound = inputs.lanBound,
        )
        if (configured is ServiceStartDecision.Stop) {
            return RecoveryDecision.NoAction(configured.reason)
        }
        if (!inputs.listenerPermission) {
            return RecoveryDecision.Blocked(RecoveryIssue.NOTIFICATION_ACCESS_REQUIRED)
        }
        if (!inputs.postPermission) {
            return RecoveryDecision.Blocked(RecoveryIssue.POST_NOTIFICATIONS_REQUIRED)
        }
        if (inputs.serviceActive) return RecoveryDecision.AlreadyRunning
        return RecoveryDecision.Start(configured as ServiceStartDecision.Start)
    }

    /** Applies the same eligibility rules to Android's sticky service recreation. */
    fun decideServiceStart(inputs: RecoveryInputs): ServiceStartDecision = when (
        val decision = decide(inputs.copy(serviceActive = false))
    ) {
        is RecoveryDecision.Start -> decision.service
        is RecoveryDecision.NoAction -> ServiceStartDecision.Stop(decision.reason)
        is RecoveryDecision.Blocked -> ServiceStartDecision.Stop(decision.issue.code)
        RecoveryDecision.AlreadyRunning -> error("service-active input was cleared")
    }
}

/**
 * Coalesces the gap between asking Android to start the service and receiving its onCreate.
 * Process death clears this process-local fence; a bounded window prevents a stale request
 * from suppressing a later Android-permitted wake.
 */
internal class RecoveryStartGate(
    private val coalesceMillis: Long = 10_000L,
) {
    private var requestedAt: Long? = null

    @Synchronized
    fun reserve(serviceActive: Boolean, nowMillis: Long): Boolean {
        if (serviceActive) {
            requestedAt = null
            return false
        }
        val previous = requestedAt
        if (previous != null && nowMillis - previous < coalesceMillis) return false
        requestedAt = nowMillis
        return true
    }

    @Synchronized
    fun release() {
        requestedAt = null
    }

    @Synchronized
    fun serviceBecameActive() {
        requestedAt = null
    }
}

internal sealed interface RecoveryExecution {
    data object Started : RecoveryExecution
    data object Coalesced : RecoveryExecution
    data object AlreadyRunning : RecoveryExecution
    data class NoAction(val reason: String) : RecoveryExecution
    data class Blocked(val issue: RecoveryIssue) : RecoveryExecution
}

internal fun executeRecoveryStart(
    decision: RecoveryDecision,
    gate: RecoveryStartGate,
    serviceActive: Boolean,
    nowMillis: Long,
    start: (ServiceStartDecision.Start) -> Unit,
    classifyFailure: (Throwable) -> RecoveryIssue,
): RecoveryExecution = when (decision) {
    RecoveryDecision.AlreadyRunning -> RecoveryExecution.AlreadyRunning
    is RecoveryDecision.NoAction -> RecoveryExecution.NoAction(decision.reason)
    is RecoveryDecision.Blocked -> RecoveryExecution.Blocked(decision.issue)
    is RecoveryDecision.Start -> {
        if (!gate.reserve(serviceActive, nowMillis)) {
            RecoveryExecution.Coalesced
        } else {
            try {
                start(decision.service)
                RecoveryExecution.Started
            } catch (failure: Throwable) {
                gate.release()
                RecoveryExecution.Blocked(classifyFailure(failure))
            }
        }
    }
}

internal fun notificationListenerAccessAvailable(context: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(context.applicationContext)
        .contains(context.packageName)

/** The sole lifecycle-recovery authority. It never changes durable user intent. */
internal object TransportRecoveryAuthority {
    private val mutex = Mutex()
    private val startGate = RecoveryStartGate()

    internal fun serviceBecameActive() {
        startGate.serviceBecameActive()
        SyncServiceStatus.setRecoveryIssue(null)
    }

    internal suspend fun recover(
        context: Context,
        trigger: RecoveryTrigger,
        nowMillis: Long = System.currentTimeMillis(),
    ): RecoveryExecution = mutex.withLock {
        val appContext = context.applicationContext
        val config = ServiceConfigStore.read(appContext)
        val peer = PeerStore.load(appContext)
        val listenerPermission = notificationListenerAccessAvailable(appContext)
        val postPermission = effectivePostAvailability(appContext)
        val serviceActive = SyncService.isActive()

        SyncServiceStatus.setEnabled(config.enabled)
        SyncServiceStatus.setListenerHealth(
            connected = SyncServiceStatus.health.value.listenerConnected,
            permission = listenerPermission,
        )
        SyncServiceStatus.setPostPermission(postPermission)

        val decision = RecoveryPolicy.decide(
            RecoveryInputs(
                persisted = config,
                paired = peer != null,
                lanBound = peer?.lanBindingId != null,
                listenerPermission = listenerPermission,
                postPermission = postPermission,
                serviceActive = serviceActive,
            ),
        )
        val result = executeRecoveryStart(
            decision = decision,
            gate = startGate,
            serviceActive = serviceActive,
            nowMillis = nowMillis,
            start = {
                appContext.startForegroundService(
                    Intent(appContext, SyncService::class.java).apply {
                        action = SyncService.ACTION_START
                        putExtra(SyncService.EXTRA_RECOVERY_TRIGGER, trigger.name.lowercase())
                    },
                )
            },
            classifyFailure = { failure ->
                if (failure !is ForegroundServiceStartNotAllowedException && failure !is SecurityException) {
                    android.util.Log.w("Twinotify", "transport recovery start failed", failure)
                }
                RecoveryIssue.BACKGROUND_START_DENIED
            },
        )
        when (result) {
            is RecoveryExecution.Blocked -> SyncServiceStatus.setRecoveryIssue(result.issue)
            is RecoveryExecution.NoAction -> SyncServiceStatus.setRecoveryIssue(null)
            RecoveryExecution.AlreadyRunning,
            RecoveryExecution.Coalesced,
            RecoveryExecution.Started -> SyncServiceStatus.setRecoveryIssue(null)
        }
        result
    }
}
