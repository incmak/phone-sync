package co.twinotify.core.service

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class SyncState { DISCONNECTED, CONNECTING, CONNECTED, LEGACY_ONLINE_ONLY, OFFLINE_QUEUED }

/** One native health snapshot used by the foreground notification and JS status event. */
data class SyncHealth(
    val service: String,
    val transport: String,
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
    val lastCallEventAt: Long? = null,
)

fun SyncHealth.toEventMap(): Map<String, Any?> = mapOf(
    "service" to service,
    "transport" to transport,
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
    "lastCallEventAt" to lastCallEventAt,
    // Keep the stable legacy key for existing JS consumers.
    "state" to when (service) {
        "connected" -> "CONNECTED"
        "connecting" -> "CONNECTING"
        "degraded" -> "OFFLINE_QUEUED"
        else -> "DISCONNECTED"
    },
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
) {
    fun toPublicMap(): Map<String, Any> = mapOf(
        "route" to route.name.lowercase(),
        "phase" to phase.name.lowercase(),
        "queued_count" to queuedCount,
    )
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

    /** A user asking to reconnect now. The coordinator uses it to cut its backoff short. */
    val routeRetryRequested: SharedFlow<Unit> = _routeRetryRequested

    fun requestRouteRetry() {
        _routeRetryRequested.tryEmit(Unit)
    }

    private val _peerUnpaired = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val peerUnpaired: SharedFlow<Unit> = _peerUnpaired

    fun setRouteStatus(status: SyncRouteStatus) {
        _routeStatus.value = status
    }

    /** A stopped service has no route. Leaving the last one would show a stale claim. */
    fun clearRouteStatus() {
        _routeStatus.value = SyncRouteStatus()
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
        _queuedCount.value = count
        _health.value = _health.value.copy(queuedCount = count, queuedBytes = bytes)
    }

    fun setProtocolFloor(floor: Int) {
        require(floor > 0) { "protocol floor must be positive" }
        _health.value = _health.value.copy(protocolFloor = floor)
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

    fun setLastReceiptAt(at: Long?) {
        _health.value = _health.value.copy(lastReceiptAt = at)
    }

    fun setLastError(code: String?) {
        _health.value = _health.value.copy(lastErrorCode = code?.take(128))
    }

    fun setCallCapture(enabled: Boolean, healthCode: String?) {
        _health.value = _health.value.copy(
            callCaptureEnabled = enabled,
            callCaptureHealthCode = healthCode?.take(64),
        )
    }

    fun setLastCallEventAt(at: Long?) {
        _health.value = _health.value.copy(lastCallEventAt = at)
    }

    fun notifyPeerUnpaired() { _peerUnpaired.tryEmit(Unit) }
}
