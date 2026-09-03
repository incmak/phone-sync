package co.twinotify.core.service

import co.twinotify.core.storage.DeliveryQueueSnapshot
import co.twinotify.core.storage.UserContentKind
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

enum class SyncState { DISCONNECTED, CONNECTING, CONNECTED, LEGACY_ONLINE_ONLY, OFFLINE_QUEUED }

/** One native health snapshot used by the foreground notification and JS status event. */
data class SyncHealth(
    val service: String,
    val transport: String,
    val enabled: Boolean,
    val protocolFloor: Int,
    val queuedCount: Int,
    val queuedBytes: Long,
    val listenerConnected: Boolean,
    val listenerPermission: Boolean,
    val postPermission: Boolean,
    val lastReceiptAt: Long?,
    val lastErrorCode: String?,
    val callCaptureEnabled: Boolean = false,
    val callCaptureHealthCode: String? = null,
    val callCaptureDisabledReason: String? = "call_capture_disabled",
    val callNotificationMode: String? = null,
    val lastCallEventAt: Long? = null,
    val totalActiveCount: Int = queuedCount,
    val totalActiveBytes: Long = queuedBytes,
)

fun SyncHealth.toEventMap(): Map<String, Any?> = mapOf(
    "service" to service,
    "transport" to transport,
    "enabled" to enabled,
    "protocolFloor" to protocolFloor,
    "queuedCount" to queuedCount,
    "queuedBytes" to queuedBytes,
    "listenerConnected" to listenerConnected,
    "listenerPermission" to listenerPermission,
    "postPermission" to postPermission,
    "lastReceiptAt" to lastReceiptAt,
    "lastErrorCode" to lastErrorCode,
    "callCaptureEnabled" to callCaptureEnabled,
    "callCaptureHealthCode" to callCaptureHealthCode,
    "callCaptureDisabledReason" to callCaptureDisabledReason,
    "callNotificationMode" to callNotificationMode,
    "lastCallEventAt" to lastCallEventAt,
    "totalActiveCount" to totalActiveCount,
    "totalActiveBytes" to totalActiveBytes,
    // Keep the stable legacy key for existing JS consumers.
    "state" to when (service) {
        "connected" -> "CONNECTED"
        "connecting" -> "CONNECTING"
        "degraded" -> "OFFLINE_QUEUED"
        else -> "DISCONNECTED"
    },
)

enum class DeliveryReason {
    NONE,
    NO_ROUTE,
    WAITING_FOR_PEER,
    RELAY_HOLDING,
    LAN_BOOTSTRAP_WAITING,
    LAN_BINDING_CONFLICT,
    PEER_VERSION_INCOMPATIBLE,
}

data class DeliveryConditions(
    val bootstrapWaiting: Boolean = false,
    val bindingConflict: Boolean = false,
    val peerVersionIncompatible: Boolean = false,
)

/**
 * What the app is allowed to render about delivery. It names the route and its phase
 * and nothing else: no endpoint, address, SSID, port, or peer identifier ever reaches
 * this type, so a UI cannot leak private network detail by rendering it.
 */
data class SyncRouteStatus(
    val route: RouteKind = RouteKind.NONE,
    val phase: RoutePhase = RoutePhase.IDLE,
    val queuedCount: Int = 0,
    val pendingLocalCount: Int = queuedCount,
    val awaitingPeerCount: Int = 0,
    val heldByRelayCount: Int = 0,
    val peerEvidence: PeerEvidence = PeerEvidence.UNKNOWN,
    val deliveryReason: DeliveryReason = DeliveryReason.NONE,
    val userContentKind: UserContentKind = UserContentKind.NOTIFICATIONS,
    val routeGeneration: Int = 0,
    val recoveryIssue: RecoveryIssue? = null,
) {
    fun toPublicMap(): Map<String, Any?> = mapOf(
        "route" to route.name.lowercase(),
        "phase" to phase.name.lowercase(),
        "queued_count" to queuedCount,
        "pending_local_count" to pendingLocalCount,
        "awaiting_peer_count" to awaitingPeerCount,
        "held_by_relay_count" to heldByRelayCount,
        "peer_evidence" to peerEvidence.name.lowercase(),
        "delivery_reason" to deliveryReason.name.lowercase(),
        "user_content_kind" to userContentKind.name.lowercase(),
        "route_generation" to routeGeneration,
        "recovery_issue" to recoveryIssue?.code,
        "presentation" to DeliveryStatusPresenter.present(
            status = this,
            paired = true,
            enabled = true,
        ).toPublicMap(),
    )
}

internal data class DeliveryPresentation(
    val state: String,
    val label: String,
    val explanation: String,
    val action: String?,
    val queuedCount: Int,
    val peerLine: String?,
) {
    fun toPublicMap(): Map<String, Any?> = mapOf(
        "state" to state,
        "label" to label,
        "explanation" to explanation,
        "action" to action,
        "queued_count" to queuedCount,
        "peer_line" to peerLine,
    )
}

/** The single native source of user-facing delivery and custody wording. */
internal object DeliveryStatusPresenter {
    fun present(status: SyncRouteStatus, paired: Boolean, enabled: Boolean = true): DeliveryPresentation {
        val queued = status.pendingLocalCount.coerceAtLeast(0)
        val awaitingPeer = status.awaitingPeerCount.coerceAtLeast(0)
        val heldByRelay = status.heldByRelayCount.coerceAtLeast(0)

        if (!paired) return DeliveryPresentation(
            state = "unpaired",
            label = "Not paired",
            explanation = "Link your other phone to start mirroring notifications.",
            action = "pair",
            queuedCount = 0,
            peerLine = null,
        )
        if (!enabled) return DeliveryPresentation(
            state = "paused",
            label = "Paused",
            explanation = "Turn on mirroring when you want delivery to resume.",
            action = null,
            queuedCount = queued,
            peerLine = null,
        )
        when (status.recoveryIssue) {
            RecoveryIssue.NOTIFICATION_ACCESS_REQUIRED -> return DeliveryPresentation(
                state = "stopped",
                label = "Notification access needed",
                explanation = "Allow notification access to resume mirroring.",
                action = "permissions",
                queuedCount = queued,
                peerLine = null,
            )
            RecoveryIssue.POST_NOTIFICATIONS_REQUIRED -> return DeliveryPresentation(
                state = "stopped",
                label = "Notifications need attention",
                explanation = "Allow Twinotify notifications to resume mirroring.",
                action = "permissions",
                queuedCount = queued,
                peerLine = null,
            )
            RecoveryIssue.BACKGROUND_START_DENIED -> return DeliveryPresentation(
                state = "stopped",
                label = "Open Twinotify to resume",
                explanation = "Android prevented the automatic restart. Try again while Twinotify is open.",
                action = "retry",
                queuedCount = queued,
                peerLine = null,
            )
            null -> Unit
        }
        if (status.phase == RoutePhase.IDLE) return DeliveryPresentation(
            state = "stopped",
            label = "Stopped",
            explanation = if (queued > 0) {
                "${itemName(queued, status.userContentKind)} ${if (queued == 1) "is" else "are"} waiting on this phone. Start mirroring to send."
            } else {
                "Mirroring is stopped."
            },
            action = "retry",
            queuedCount = queued,
            peerLine = null,
        )
        if (status.phase == RoutePhase.AUTHENTICATED && status.route == RouteKind.LAN) {
            return DeliveryPresentation(
                state = "direct",
                label = "Direct on Wi-Fi",
                explanation = "Your phones are talking directly over Wi-Fi.",
                action = null,
                queuedCount = queued,
                peerLine = "Reachable now",
            )
        }
        if (status.phase == RoutePhase.AUTHENTICATED && status.route == RouteKind.BLUETOOTH) {
            return DeliveryPresentation(
                state = "direct",
                label = "Direct Bluetooth",
                explanation = "Your phones are talking directly over Bluetooth.",
                action = null,
                queuedCount = queued,
                peerLine = "Reachable now",
            )
        }
        if (status.phase == RoutePhase.AUTHENTICATED && status.route == RouteKind.RELAY) {
            val explanation = when {
                status.deliveryReason == DeliveryReason.LAN_BINDING_CONFLICT ->
                    "Direct Wi-Fi needs attention. Relay delivery remains encrypted end to end."
                status.deliveryReason == DeliveryReason.PEER_VERSION_INCOMPATIBLE ->
                    "Update Twinotify on your other phone to enable direct Wi-Fi."
                heldByRelay > 0 ->
                    "${itemName(heldByRelay, status.userContentKind)} ${if (heldByRelay == 1) "is" else "are"} stored securely and waiting for your other phone."
                awaitingPeer > 0 ->
                    "${itemName(awaitingPeer, status.userContentKind)} ${if (awaitingPeer == 1) "is" else "are"} waiting for confirmation from your other phone."
                status.deliveryReason == DeliveryReason.LAN_BOOTSTRAP_WAITING ->
                    "Setting up direct Wi-Fi in the background. Delivery is encrypted end to end."
                status.peerEvidence == PeerEvidence.RECENT ->
                    "Your other phone checked in recently. Delivery is encrypted end to end."
                else -> "Connected to the relay. Waiting for your other phone to check in."
            }
            return DeliveryPresentation(
                state = "relay",
                label = "Via relay",
                explanation = explanation,
                action = null,
                queuedCount = queued,
                peerLine = evidenceLine(status),
            )
        }
        if (queued > 0) return DeliveryPresentation(
            state = "queued",
            label = "Queued on this phone",
            explanation = "${itemName(queued, status.userContentKind)} will send when a connection is available.",
            action = "retry",
            queuedCount = queued,
            peerLine = "Not confirmed online",
        )
        if (heldByRelay > 0) return DeliveryPresentation(
            state = "reconnecting",
            label = "Reconnecting",
            explanation = "${itemName(heldByRelay, status.userContentKind)} ${if (heldByRelay == 1) "is" else "are"} stored securely while this phone reconnects.",
            action = "retry",
            queuedCount = queued,
            peerLine = "Not confirmed online",
        )
        return DeliveryPresentation(
            state = "reconnecting",
            label = "Reconnecting",
            explanation = "Looking for your other phone. This retries on its own.",
            action = null,
            queuedCount = queued,
            peerLine = "Not confirmed online",
        )
    }

    private fun evidenceLine(status: SyncRouteStatus): String = when {
        status.route.isDirect && status.phase == RoutePhase.AUTHENTICATED -> "Reachable now"
        status.peerEvidence == PeerEvidence.RECENT -> "Checked in recently"
        else -> "Not confirmed online"
    }

    private fun itemName(total: Int, kind: UserContentKind): String {
        val noun = if (kind == UserContentKind.NOTIFICATIONS) "notification" else "sync update"
        return "$total $noun${if (total == 1) "" else "s"}"
    }
}

internal object DeliveryStatusModel {
    fun resolve(
        status: SyncRouteStatus,
        queue: DeliveryQueueSnapshot,
        conditions: DeliveryConditions,
        relayEvidence: PeerEvidence,
    ): SyncRouteStatus {
        val evidence = if (
            status.route.isDirect && status.phase == RoutePhase.AUTHENTICATED
        ) {
            PeerEvidence.DIRECT
        } else {
            relayEvidence.takeUnless { it == PeerEvidence.DIRECT } ?: PeerEvidence.UNKNOWN
        }
        val reason = when {
            conditions.bindingConflict -> DeliveryReason.LAN_BINDING_CONFLICT
            conditions.peerVersionIncompatible -> DeliveryReason.PEER_VERSION_INCOMPATIBLE
            status.route == RouteKind.NONE && queue.pendingLocal > 0 -> DeliveryReason.NO_ROUTE
            queue.heldByRelay > 0 -> DeliveryReason.RELAY_HOLDING
            queue.awaitingPeer > 0 -> DeliveryReason.WAITING_FOR_PEER
            conditions.bootstrapWaiting -> DeliveryReason.LAN_BOOTSTRAP_WAITING
            else -> DeliveryReason.NONE
        }
        return status.copy(
            queuedCount = queue.pendingLocal,
            pendingLocalCount = queue.pendingLocal,
            awaitingPeerCount = queue.awaitingPeer,
            heldByRelayCount = queue.heldByRelay,
            peerEvidence = evidence,
            deliveryReason = reason,
            userContentKind = queue.userContentKind,
        )
    }
}

internal fun RouteHealth.toSyncRouteStatus(): SyncRouteStatus = SyncRouteStatus(
    route = active,
    phase = phase,
    queuedCount = queuedCount,
    pendingLocalCount = queuedCount,
)

internal fun SyncRouteStatus.toSyncState(protocolFloor: Int = 2): SyncState = when (phase) {
    RoutePhase.AUTHENTICATED -> if (route == RouteKind.RELAY && protocolFloor == 1) {
        SyncState.LEGACY_ONLINE_ONLY
    } else {
        SyncState.CONNECTED
    }
    RoutePhase.CONNECTING -> SyncState.CONNECTING
    RoutePhase.RECONNECTING -> SyncState.OFFLINE_QUEUED
    RoutePhase.IDLE -> SyncState.DISCONNECTED
}

object SyncServiceStatus {
    private val _state = MutableStateFlow(SyncState.DISCONNECTED)
    val state: StateFlow<SyncState> = _state
    private val _queuedCount = MutableStateFlow(0)
    val queuedCount: StateFlow<Int> = _queuedCount
    private val _health = MutableStateFlow(
        SyncHealth(
            service = "stopped",
            transport = "offline",
            enabled = false,
            protocolFloor = 1,
            queuedCount = 0,
            queuedBytes = 0,
            listenerConnected = false,
            listenerPermission = false,
            postPermission = false,
            lastReceiptAt = null,
            lastErrorCode = null,
        ),
    )
    val health: StateFlow<SyncHealth> = _health
    private val _routeStatus = MutableStateFlow(SyncRouteStatus())
    val routeStatus: StateFlow<SyncRouteStatus> = _routeStatus
    private val _routeRetryRequested = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    private val routeGeneration = AtomicInteger(0)
    private var queueSnapshot = emptyQueueSnapshot()
    private var deliveryConditions = DeliveryConditions()
    private var relayEvidence = PeerEvidence.UNKNOWN

    /** A user asking to reconnect now. The coordinator uses it to cut its backoff short. */
    val routeRetryRequested: SharedFlow<Unit> = _routeRetryRequested

    fun requestRouteRetry() {
        _routeRetryRequested.tryEmit(Unit)
    }

    private val _peerUnpaired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val peerUnpaired: SharedFlow<Unit> = _peerUnpaired

    @Synchronized
    fun setRouteStatus(status: SyncRouteStatus, generation: Int = routeGeneration.get()) {
        if (generation != routeGeneration.get()) return
        val statusQueue = DeliveryQueueSnapshot(
            pendingLocal = status.pendingLocalCount,
            awaitingPeer = status.awaitingPeerCount,
            heldByRelay = status.heldByRelayCount,
            internalActive = queueSnapshot.internalActive,
            totalActive = queueSnapshot.totalActive,
            totalActiveBytes = queueSnapshot.totalActiveBytes,
            userContentKind = status.userContentKind,
        )
        _routeStatus.value = DeliveryStatusModel.resolve(
            status.copy(routeGeneration = generation),
            statusQueue,
            deliveryConditions,
            relayEvidence,
        )
    }

    @Synchronized
    fun beginRouteGeneration(): Int {
        val next = routeGeneration.incrementAndGet()
        deliveryConditions = DeliveryConditions()
        relayEvidence = PeerEvidence.UNKNOWN
        _routeStatus.value = DeliveryStatusModel.resolve(
            SyncRouteStatus(route = RouteKind.NONE, phase = RoutePhase.CONNECTING, routeGeneration = next),
            queueSnapshot,
            deliveryConditions,
            relayEvidence,
        )
        return next
    }

    /** A stopped service has no route. Leaving the last one would show a stale claim. */
    @Synchronized
    fun clearRouteStatus() {
        queueSnapshot = emptyQueueSnapshot()
        deliveryConditions = DeliveryConditions()
        relayEvidence = PeerEvidence.UNKNOWN
        _routeStatus.update {
            SyncRouteStatus(
                routeGeneration = routeGeneration.get(),
                recoveryIssue = it.recoveryIssue,
            )
        }
    }

    fun setState(s: SyncState) {
        _state.value = s
        _health.value = _health.value.copy(
            service = when (s) {
                SyncState.DISCONNECTED -> "stopped"
                SyncState.CONNECTING -> "connecting"
                SyncState.CONNECTED -> "connected"
                SyncState.LEGACY_ONLINE_ONLY -> "degraded"
                SyncState.OFFLINE_QUEUED -> "degraded"
            },
            transport = when (s) {
                SyncState.CONNECTED, SyncState.LEGACY_ONLINE_ONLY -> "online"
                SyncState.CONNECTING -> "connecting"
                SyncState.DISCONNECTED, SyncState.OFFLINE_QUEUED -> "offline"
            },
            // A successful authenticated connection supersedes transient startup/transport
            // failures; retaining them would report a stale error alongside healthy state.
            lastErrorCode = if (s == SyncState.CONNECTED) null else _health.value.lastErrorCode,
        )
    }

    fun setQueuedCount(n: Int) {
        setQueueStats(n, _health.value.queuedBytes)
    }

    fun setQueueStats(count: Int, bytes: Long) {
        require(count >= 0) { "queued count must be non-negative" }
        require(bytes >= 0) { "queued bytes must be non-negative" }
        setQueueSnapshot(
            DeliveryQueueSnapshot(
                pendingLocal = count,
                awaitingPeer = _routeStatus.value.awaitingPeerCount,
                heldByRelay = _routeStatus.value.heldByRelayCount,
                internalActive = 0,
                totalActive = count,
                totalActiveBytes = bytes,
                userContentKind = _routeStatus.value.userContentKind,
            ),
        )
    }

    @Synchronized
    fun setQueueSnapshot(snapshot: DeliveryQueueSnapshot, generation: Int = routeGeneration.get()) {
        if (generation != routeGeneration.get()) return
        requireValidSnapshot(snapshot)
        queueSnapshot = snapshot
        _queuedCount.value = snapshot.pendingLocal
        _health.value = _health.value.copy(
            queuedCount = snapshot.pendingLocal,
            queuedBytes = snapshot.totalActiveBytes,
            totalActiveCount = snapshot.totalActive,
            totalActiveBytes = snapshot.totalActiveBytes,
        )
        _routeStatus.value = DeliveryStatusModel.resolve(
            _routeStatus.value.copy(routeGeneration = generation),
            snapshot,
            deliveryConditions,
            relayEvidence,
        )
    }

    /** Publishes route, classified custody, and evidence as one generation-fenced snapshot. */
    @Synchronized
    fun setRouteSnapshot(
        status: SyncRouteStatus,
        snapshot: DeliveryQueueSnapshot,
        evidence: PeerEvidence,
        generation: Int = routeGeneration.get(),
    ) {
        if (generation != routeGeneration.get()) return
        requireValidSnapshot(snapshot)
        queueSnapshot = snapshot
        relayEvidence = evidence
        _queuedCount.value = snapshot.pendingLocal
        _health.value = _health.value.copy(
            queuedCount = snapshot.pendingLocal,
            queuedBytes = snapshot.totalActiveBytes,
            totalActiveCount = snapshot.totalActive,
            totalActiveBytes = snapshot.totalActiveBytes,
        )
        _routeStatus.value = DeliveryStatusModel.resolve(
            status.copy(routeGeneration = generation),
            snapshot,
            deliveryConditions,
            relayEvidence,
        )
    }

    @Synchronized
    fun setDeliveryConditions(conditions: DeliveryConditions, generation: Int = routeGeneration.get()) {
        if (generation != routeGeneration.get()) return
        deliveryConditions = conditions
        _routeStatus.value = DeliveryStatusModel.resolve(
            _routeStatus.value.copy(routeGeneration = generation),
            queueSnapshot,
            deliveryConditions,
            relayEvidence,
        )
    }

    @Synchronized
    fun setPeerEvidence(evidence: PeerEvidence, generation: Int = routeGeneration.get()) {
        if (generation != routeGeneration.get()) return
        relayEvidence = evidence
        _routeStatus.value = DeliveryStatusModel.resolve(
            _routeStatus.value.copy(routeGeneration = generation),
            queueSnapshot,
            deliveryConditions,
            relayEvidence,
        )
    }

    fun setProtocolFloor(floor: Int) {
        require(floor > 0) { "protocol floor must be positive" }
        _health.value = _health.value.copy(protocolFloor = floor)
        val route = _routeStatus.value
        if (route.phase == RoutePhase.AUTHENTICATED) {
            setState(route.toSyncState(floor))
        }
    }

    fun setListenerHealth(connected: Boolean, permission: Boolean) {
        _health.value = _health.value.copy(
            listenerConnected = connected,
            listenerPermission = permission,
        )
    }

    fun setPostPermission(granted: Boolean) {
        _health.value = _health.value.copy(postPermission = granted)
    }

    fun setEnabled(enabled: Boolean) {
        _health.value = _health.value.copy(enabled = enabled)
    }

    @Synchronized
    fun setRecoveryIssue(issue: RecoveryIssue?) {
        _routeStatus.update { it.copy(recoveryIssue = issue) }
    }

    fun setLastReceiptAt(at: Long?) {
        _health.value = _health.value.copy(lastReceiptAt = at)
    }

    fun setLastError(code: String?) {
        _health.value = _health.value.copy(lastErrorCode = code?.take(128))
    }

    /** Truthful call-notification capability modes reported by the materializer; never health codes. */
    private val CALL_NOTIFICATION_MODES = setOf(
        "call_style_deferred_no_controls",
        "call_style_conditional_controls",
    )

    fun setCallCapture(
        enabled: Boolean,
        healthCode: String?,
        notificationMode: String? = null,
    ) {
        val capabilityMode = notificationMode ?: healthCode
            ?.takeIf { enabled && it in CALL_NOTIFICATION_MODES }
        val boundedHealth = healthCode
            ?.takeUnless { it == capabilityMode }
            ?.take(64)
        _health.value = _health.value.copy(
            callCaptureEnabled = enabled,
            callCaptureHealthCode = if (enabled) boundedHealth else null,
            callCaptureDisabledReason = if (enabled) null else healthCode?.take(64),
            callNotificationMode = if (enabled) capabilityMode?.take(64) else null,
        )
    }

    fun setLastCallEventAt(at: Long?) {
        _health.value = _health.value.copy(lastCallEventAt = at)
    }

    fun notifyPeerUnpaired() { _peerUnpaired.tryEmit(Unit) }

    private fun requireValidSnapshot(snapshot: DeliveryQueueSnapshot) {
        require(
            snapshot.pendingLocal >= 0 && snapshot.awaitingPeer >= 0 && snapshot.heldByRelay >= 0 &&
                snapshot.internalActive >= 0 && snapshot.totalActive >= 0 && snapshot.totalActiveBytes >= 0,
        ) { "delivery queue snapshot must be non-negative" }
    }

    private fun emptyQueueSnapshot() = DeliveryQueueSnapshot(
        pendingLocal = 0,
        awaitingPeer = 0,
        heldByRelay = 0,
        internalActive = 0,
        totalActive = 0,
        totalActiveBytes = 0,
        userContentKind = UserContentKind.NOTIFICATIONS,
    )
}
