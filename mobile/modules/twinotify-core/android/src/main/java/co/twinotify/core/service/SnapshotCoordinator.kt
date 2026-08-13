package co.twinotify.core.service

import co.twinotify.core.listener.CanonIdBuilder
import co.twinotify.core.listener.NotifPostBuilder
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.listener.NotificationListenerBridge
import co.twinotify.core.listener.SourceNotificationSnapshot
import co.twinotify.core.protocol.InnerEventV2
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.ReliableDeliveryDao
import co.twinotify.core.storage.SnapshotBeginResult
import co.twinotify.core.storage.SnapshotCommitResult
import co.twinotify.core.storage.SnapshotStage
import co.twinotify.core.storage.SnapshotStageResult
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONObject

/** The anti-entropy summary exchanged for one canonical origin. */
data class StateDigest(
    val originDevice: String,
    val count: Int,
    val digest: String,
    val originEpoch: String = originDevice,
) {
    val itemCount: Int get() = count
}

data class SnapshotBeginEvent(
    val snapshotId: String,
    val originDevice: String,
    val itemCount: Int,
    val originEpoch: String = originDevice,
)

data class SnapshotItemEvent(
    val snapshotId: String,
    val originDevice: String,
    val canonId: String,
    val sequence: Long,
    val payloadJson: String,
)

data class SnapshotEndEvent(
    val snapshotId: String,
    val originDevice: String,
    val digest: String,
)

sealed interface SnapshotConvergence {
    data object Match : SnapshotConvergence
    data class RepairStarted(val snapshotId: String, val itemCount: Int) : SnapshotConvergence
    data object RateLimited : SnapshotConvergence
    data object SourceUnavailable : SnapshotConvergence
    data class Rejected(val reason: String) : SnapshotConvergence
    data class Committed(val upserted: Int, val cancelled: Int) : SnapshotConvergence
    data class Incomplete(val expected: Int, val staged: Int) : SnapshotConvergence
    data class DigestMismatch(val expected: String, val actual: String) : SnapshotConvergence
}

/** Persistence boundary kept small so staging and validation remain unit-testable without Room. */
interface SnapshotStore {
    suspend fun activeOriginStates(originDevice: String): List<CanonicalNotificationState>
    suspend fun beginSnapshot(
        snapshotId: String,
        originDevice: String,
        expectedItemCount: Int,
        receivedAt: Long,
    ): SnapshotBeginResult
    suspend fun stageSnapshotItem(row: SnapshotStage): SnapshotStageResult
    suspend fun stageSnapshotItem(row: SnapshotStage, expectedOriginDevice: String): SnapshotStageResult =
        stageSnapshotItem(row)
    suspend fun commitSnapshot(snapshotId: String, expectedDigest: String, committedAt: Long): SnapshotCommitResult
    suspend fun commitSnapshot(
        snapshotId: String,
        expectedDigest: String,
        committedAt: Long,
        expectedOriginDevice: String,
    ): SnapshotCommitResult = commitSnapshot(snapshotId, expectedDigest, committedAt)
    suspend fun expireSnapshotStages(cutoff: Long): Int = 0
}

class DaoSnapshotStore(private val dao: ReliableDeliveryDao) : SnapshotStore {
    override suspend fun activeOriginStates(originDevice: String): List<CanonicalNotificationState> =
        dao.activeOriginStates(originDevice)

    override suspend fun beginSnapshot(
        snapshotId: String,
        originDevice: String,
        expectedItemCount: Int,
        receivedAt: Long,
    ): SnapshotBeginResult = dao.beginSnapshot(snapshotId, originDevice, expectedItemCount, receivedAt)

    override suspend fun stageSnapshotItem(row: SnapshotStage): SnapshotStageResult = dao.stageSnapshotItem(row)

    override suspend fun stageSnapshotItem(row: SnapshotStage, expectedOriginDevice: String): SnapshotStageResult =
        dao.stageSnapshotItem(row, expectedOriginDevice)

    override suspend fun commitSnapshot(
        snapshotId: String,
        expectedDigest: String,
        committedAt: Long,
    ): SnapshotCommitResult = dao.commitSnapshot(snapshotId, expectedDigest, committedAt)

    override suspend fun commitSnapshot(
        snapshotId: String,
        expectedDigest: String,
        committedAt: Long,
        expectedOriginDevice: String,
    ): SnapshotCommitResult = dao.commitSnapshot(snapshotId, expectedDigest, committedAt, expectedOriginDevice)

    override suspend fun expireSnapshotStages(cutoff: Long): Int = dao.expireSnapshotStages(cutoff)
}

/** Outbound boundary for encrypted transport integration. Every event is emitted independently. */
fun interface SnapshotEmitter {
    suspend fun emit(event: Any)
}

/** Source boundary used to enumerate active notifications when anti-entropy detects a mismatch. */
fun interface SnapshotSource {
    fun active(originDevice: String): List<SourceNotificationSnapshot>

    fun available(): Boolean = true

    /** Build a bounded notification payload without requiring a framework context in tests. */
    fun payloadJson(originDevice: String, snapshot: SourceNotificationSnapshot): String =
        NotifPostBuilder.toPayloadJson(
            NotifPostJson(
                type = "notif.post",
                canon_id = CanonIdBuilder.build(originDevice, snapshot.packageName, snapshot.id, snapshot.tag),
                app_name = snapshot.appName,
                package_name = snapshot.packageName,
                id = snapshot.id,
                tag = snapshot.tag,
                title = snapshot.title,
                text = snapshot.text,
                sub_text = snapshot.subText,
                big_text = snapshot.bigText,
                visibility = when (snapshot.visibility) {
                    android.app.Notification.VISIBILITY_PUBLIC -> "public"
                    android.app.Notification.VISIBILITY_SECRET -> "secret"
                    else -> "private"
                },
                is_group_summary = snapshot.isGroupSummary,
                is_ongoing = snapshot.isOngoing,
                is_clearable = snapshot.isClearable,
                small_icon_png_b64 = snapshot.smallIconPngB64,
                large_icon_png_b64 = snapshot.largeIconPngB64,
                ts = snapshot.postTime,
            ),
        )
}

class ListenerSnapshotSource(
    private val context: android.content.Context,
    private val denylist: Set<String> = emptySet(),
) : SnapshotSource {
    override fun active(originDevice: String): List<SourceNotificationSnapshot> =
        NotificationListenerBridge.activeSourceSnapshots(context.applicationContext, denylist)

    override fun available(): Boolean = NotificationListenerBridge.isAttached()

    override fun payloadJson(originDevice: String, snapshot: SourceNotificationSnapshot): String =
        NotifPostBuilder.toPayloadJson(
            NotifPostBuilder.build(snapshot, context.applicationContext, originDevice, "notif.post"),
        )
}

/**
 * Stages encrypted snapshot payloads until an authenticated end digest proves completeness. This
 * class never invokes Android notification APIs; the normal materializer consumes the committed
 * canonical rows after [onEnd] returns [SnapshotConvergence.Committed].
 */
class SnapshotCoordinator(
    private val store: SnapshotStore,
    private val emitter: SnapshotEmitter? = null,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val source: SnapshotSource? = null,
    private val localOriginDevice: String? = null,
    private val snapshotIntervalMs: Long = DEFAULT_SNAPSHOT_INTERVAL_MS,
) {
    constructor(
        dao: ReliableDeliveryDao,
        emitter: SnapshotEmitter? = null,
        clock: () -> Long = { System.currentTimeMillis() },
        source: SnapshotSource? = null,
        localOriginDevice: String? = null,
    ) : this(DaoSnapshotStore(dao), emitter, clock, source, localOriginDevice)

    private val lastRepairAt = HashMap<String, Long>()
    private val begunOrigins = HashMap<String, String>()

    suspend fun sweepExpired(now: Long = clock()): Int =
        store.expireSnapshotStages(now - SNAPSHOT_TTL_MS)

    suspend fun emitLocalDigest(originDevice: String) {
        val digest = localDigest(originDevice)
        emitter?.emit(digest) ?: throw IllegalStateException("snapshot emitter unavailable")
    }

    suspend fun localDigest(originDevice: String): StateDigest {
        require(originDevice.isNotEmpty()) { "snapshot origin must not be empty" }
        val states = store.activeOriginStates(originDevice).filter { it.state == "ACTIVE" }
        return StateDigest(
            originDevice = originDevice,
            count = states.size,
            digest = digestStates(states),
            originEpoch = originDevice,
        )
    }

    /** Compares a peer summary and emits a bounded repair snapshot when the summaries diverge. */
    suspend fun onDigest(
        remote: StateDigest,
        force: Boolean = false,
        localDevice: String? = localOriginDevice,
    ): SnapshotConvergence {
        require(remote.originDevice.isNotEmpty()) { "digest origin must not be empty" }
        require(remote.count in 0..MAX_SNAPSHOT_ITEMS) { "digest item count is out of bounds" }
        require(remote.digest.matches(DIGEST_PATTERN)) { "digest must be lower-case SHA-256" }
        val localOrigin = localDevice ?: return SnapshotConvergence.SourceUnavailable
        // A digest describes one canonical origin. Compare against our durable copy of that
        // origin, not blindly against this device's own source origin.
        val local = localDigest(remote.originDevice)
        if (local.count == remote.count && local.digest == remote.digest && local.originEpoch == remote.originEpoch) {
            return SnapshotConvergence.Match
        }
        // Only the origin owner can enumerate source notifications and author a repair snapshot.
        // A receiver reports divergence to its transport layer instead of emitting a snapshot
        // with the wrong origin (which would be rejected by the DAO ownership guard).
        if (remote.originDevice != localOrigin) return SnapshotConvergence.SourceUnavailable
        val now = clock()
        val previous = lastRepairAt[remote.originDevice]
        if (!force && previous != null && now - previous < snapshotIntervalMs) {
            return SnapshotConvergence.RateLimited
        }
        val snapshotSource = source ?: return SnapshotConvergence.SourceUnavailable
        if (!snapshotSource.available()) return SnapshotConvergence.SourceUnavailable
        val snapshots = snapshotSource.active(localOrigin)
        if (snapshots.isEmpty() && local.count != 0) return SnapshotConvergence.SourceUnavailable
        if (snapshots.size > MAX_SNAPSHOT_ITEMS) {
            return SnapshotConvergence.Rejected("active notification count exceeds snapshot bound")
        }
        val snapshotId = UUID.randomUUID().toString()
        val states = store.activeOriginStates(localOrigin).associateBy { it.canonId }
        val items = snapshots.mapNotNull { snapshot ->
            val canonId = CanonIdBuilder.build(localOrigin, snapshot.packageName, snapshot.id, snapshot.tag)
            val payload = snapshotSource.payloadJson(localOrigin, snapshot)
            val sequence = states[canonId]?.latestSequence ?: 1L
            SnapshotItemEvent(snapshotId, localOrigin, canonId, sequence, payload)
        }
        val oversized = items.any { it.payloadJson.toByteArray(Charsets.UTF_8).size > MAX_ITEM_PAYLOAD_BYTES }
        val actual = digestItems(items)
        if (oversized) return SnapshotConvergence.Rejected("snapshot item exceeds bounded payload size")
        if (items.size != local.count || actual != local.digest) {
            return SnapshotConvergence.Rejected("active notification enumeration changed during snapshot")
        }
        val sink = emitter ?: return SnapshotConvergence.SourceUnavailable
        lastRepairAt[remote.originDevice] = now
        sink.emit(SnapshotBeginEvent(snapshotId, localOrigin, items.size, local.originEpoch))
        try {
            items.forEach { item -> sink.emit(item) }
            sink.emit(SnapshotEndEvent(snapshotId, localOrigin, local.digest))
        } catch (error: Throwable) {
            return SnapshotConvergence.Rejected("snapshot emission failed: ${error.message ?: "unknown"}")
        }
        return SnapshotConvergence.RepairStarted(snapshotId, items.size)
    }

    suspend fun onBegin(begin: SnapshotBeginEvent): SnapshotConvergence {
        validateBegin(begin)
        store.expireSnapshotStages(clock() - SNAPSHOT_TTL_MS)
        return when (store.beginSnapshot(begin.snapshotId, begin.originDevice, begin.itemCount, clock())) {
            is SnapshotBeginResult.Started -> {
                begunOrigins[begin.snapshotId] = begin.originDevice
                SnapshotConvergence.RepairStarted(begin.snapshotId, begin.itemCount)
            }
        }
    }

    suspend fun onItem(item: SnapshotItemEvent): SnapshotConvergence {
        validateItem(item)
        val result = store.stageSnapshotItem(
            SnapshotStage(
                snapshotId = item.snapshotId,
                canonId = item.canonId,
                sequence = item.sequence,
                payloadJson = item.payloadJson,
                receivedAt = clock(),
            ),
            item.originDevice,
        )
        return when (result) {
            SnapshotStageResult.Staged -> SnapshotConvergence.RepairStarted(item.snapshotId, 0)
            SnapshotStageResult.MissingBegin -> SnapshotConvergence.Rejected("snapshot item arrived before begin")
            SnapshotStageResult.OriginMismatch -> SnapshotConvergence.Rejected("snapshot item origin mismatch")
        }
    }

    suspend fun onEnd(end: SnapshotEndEvent): SnapshotConvergence {
        require(end.snapshotId.isNotEmpty()) { "snapshot ID must not be empty" }
        require(end.originDevice.isNotEmpty()) { "snapshot origin must not be empty" }
        require(end.digest.matches(DIGEST_PATTERN)) { "snapshot digest must be lower-case SHA-256" }
        val begunOrigin = begunOrigins[end.snapshotId]
        if (begunOrigin != null && begunOrigin != end.originDevice) {
            return SnapshotConvergence.Rejected("snapshot origin changed")
        }
        return when (val result = store.commitSnapshot(
            end.snapshotId,
            end.digest,
            clock(),
            end.originDevice,
        )) {
            is SnapshotCommitResult.Committed -> SnapshotConvergence.Committed(result.upserted, result.cancelled)
            is SnapshotCommitResult.Incomplete -> SnapshotConvergence.Incomplete(result.expected, result.staged)
            is SnapshotCommitResult.DigestMismatch -> SnapshotConvergence.DigestMismatch(result.expected, result.actual)
            is SnapshotCommitResult.Expired -> SnapshotConvergence.Rejected("snapshot expired before end (${result.snapshotAgeMs}ms)")
            SnapshotCommitResult.MissingBegin -> SnapshotConvergence.Rejected("snapshot end arrived before begin")
        }
    }

    /** Convenience adapters for authenticated protocol events. */
    suspend fun onDigest(event: InnerEventV2, force: Boolean = false): SnapshotConvergence {
        require(event.type == "state.digest") { "expected state.digest" }
        val payload = event.payloadObject()
        require(payload.optString("origin_device", event.originDevice) == event.originDevice) {
            "state.digest origin does not match authenticated event"
        }
        return onDigest(
            StateDigest(
                originDevice = payload.optString("origin_device", event.originDevice),
                count = payload.optInt("count", payload.optInt("item_count", -1)),
                digest = payload.getString("digest"),
                originEpoch = payload.optString("origin_epoch", event.originDevice),
            ),
            force,
        )
    }

    suspend fun onBegin(event: InnerEventV2): SnapshotConvergence {
        require(event.type == "state.snapshot.begin") { "expected state.snapshot.begin" }
        val p = event.payloadObject()
        require(p.optString("origin_device", event.originDevice) == event.originDevice) {
            "snapshot begin origin does not match authenticated event"
        }
        return onBegin(
            SnapshotBeginEvent(
                snapshotId = p.getString("snapshot_id"),
                originDevice = p.optString("origin_device", event.originDevice),
                itemCount = p.optInt("item_count", p.optInt("count", -1)),
                originEpoch = p.optString("origin_epoch", event.originDevice),
            ),
        )
    }

    suspend fun onItem(event: InnerEventV2): SnapshotConvergence {
        require(event.type == "state.snapshot.item") { "expected state.snapshot.item" }
        return onItem(
            SnapshotItemEvent(
                snapshotId = event.payloadObject().getString("snapshot_id"),
                originDevice = event.originDevice,
                canonId = requireNotNull(event.canonId),
                sequence = requireNotNull(event.sequence),
                payloadJson = event.payloadObject().optJSONObject("notification_payload")?.toString()
                    ?: event.payloadObject().optJSONObject("payload")?.toString()
                    ?: throw IllegalArgumentException("snapshot item requires notification_payload"),
            ),
        )
    }

    suspend fun onEnd(event: InnerEventV2): SnapshotConvergence {
        require(event.type == "state.snapshot.end") { "expected state.snapshot.end" }
        val p = event.payloadObject()
        return onEnd(
            SnapshotEndEvent(
                snapshotId = p.getString("snapshot_id"),
                originDevice = event.originDevice,
                digest = p.getString("digest"),
            ),
        )
    }

    private fun validateBegin(begin: SnapshotBeginEvent) {
        require(begin.snapshotId.isNotEmpty()) { "snapshot ID must not be empty" }
        require(begin.originDevice.isNotEmpty()) { "snapshot origin must not be empty" }
        require(begin.itemCount in 0..MAX_SNAPSHOT_ITEMS) { "snapshot item count is out of bounds" }
    }

    private fun validateItem(item: SnapshotItemEvent) {
        require(item.snapshotId.isNotEmpty()) { "snapshot ID must not be empty" }
        require(item.originDevice.isNotEmpty()) { "snapshot origin must not be empty" }
        require(item.canonId.isNotEmpty()) { "snapshot canon ID must not be empty" }
        require(item.sequence > 0) { "snapshot sequence must be positive" }
        require(item.payloadJson.toByteArray(Charsets.UTF_8).size <= MAX_ITEM_PAYLOAD_BYTES) {
            "snapshot item exceeds bounded payload size"
        }
        require(JSONObject(item.payloadJson).optString("type") in setOf("notif.post", "notif.update")) {
            "snapshot item payload must be a notification post/update"
        }
    }

    private fun digestStates(states: List<CanonicalNotificationState>): String = sha256(
        states.filter { it.state == "ACTIVE" }
            .sortedBy { it.canonId }
            .joinToString("\n") { "${it.canonId}\u0000${it.latestSequence}\u0000${it.state}" },
    )

    private fun digestItems(items: List<SnapshotItemEvent>): String = sha256(
        items.sortedBy { it.canonId }
            .joinToString("\n") { "${it.canonId}\u0000${it.sequence}\u0000ACTIVE" },
    )

    private fun sha256(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(raw.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    companion object {
        const val MAX_SNAPSHOT_ITEMS = 4_096
        const val MAX_ITEM_PAYLOAD_BYTES = 512 * 1024
        const val SNAPSHOT_TTL_MS = 10 * 60 * 1_000L
        const val DEFAULT_SNAPSHOT_INTERVAL_MS = 5 * 60 * 1_000L
        private val DIGEST_PATTERN = Regex("^[0-9a-f]{64}$")
    }
}
