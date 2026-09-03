package co.twinotify.core.call

import android.content.Context
import co.twinotify.core.actions.ActionInvocationExpiryScheduler
import co.twinotify.core.actions.PersistentActionInvocationExpiryScheduler
import co.twinotify.core.service.DefaultAndroidNotificationPort
import co.twinotify.core.service.SyncService
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.ActionInvocationOutboxCommitResult
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundMessage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class MirrorCallControlTarget(
    val canonId: String,
    val callSessionId: String,
    val callSequence: Long,
    val localTag: String,
    val localId: Int,
    val controlId: String,
    val kind: CallControlKind,
)

internal fun resolveMirrorCallControlTarget(
    identity: CallControlInvokeIdentity,
    canonId: String,
    state: CanonicalNotificationState,
): MirrorCallControlTarget? {
    if (state.state != "ACTIVE" || state.canonId != canonId || !canonId.startsWith("call:")) return null
    if (state.mirrorLocalTag != identity.mirrorTag || state.mirrorLocalId != identity.mirrorId) return null
    val payload = runCatching { JSONObject(state.desiredPayloadJson ?: return null) }.getOrNull() ?: return null
    val sessionId = payload.optString("call_session_id")
    if (canonId != "call:$sessionId") return null
    val controls = payload.optJSONArray("controls") ?: return null
    (0 until controls.length()).asSequence().mapNotNull { index ->
        runCatching { controls.getJSONObject(index) }.getOrNull()
    }.firstOrNull { descriptor ->
        descriptor.length() == 2 &&
            descriptor.optString("control_id") == identity.controlId &&
            descriptor.optString("kind") == identity.kind.wire
    } ?: return null
    return MirrorCallControlTarget(
        canonId, sessionId, state.latestSequence, identity.mirrorTag, identity.mirrorId,
        identity.controlId, identity.kind,
    )
}

fun interface MirrorCallControlTargetLoader {
    suspend fun load(identity: CallControlInvokeIdentity): MirrorCallControlTarget?
}

fun interface CallControlInvokeRowEncoder {
    suspend fun encode(input: CallControlInvokeInput): OutboundMessage
}

fun interface MirrorCallControlCommitter {
    suspend fun commit(invocation: ActionInvocation, invoke: OutboundMessage): ActionInvocationOutboxCommitResult
}

fun interface MirrorCallControlReposter {
    suspend fun repost(target: MirrorCallControlTarget)
}

sealed interface MirrorCallControlInvokeResult {
    data class Queued(val invocationId: String) : MirrorCallControlInvokeResult
    data object Gone : MirrorCallControlInvokeResult
    data object Failed : MirrorCallControlInvokeResult
}

class MirrorCallControlInvoker(
    private val loadTarget: MirrorCallControlTargetLoader,
    private val encode: CallControlInvokeRowEncoder,
    private val commit: MirrorCallControlCommitter,
    private val signalTransport: () -> Unit,
    private val scheduleExpiry: ActionInvocationExpiryScheduler,
    private val repost: MirrorCallControlReposter,
) {
    suspend fun invoke(identity: CallControlInvokeIdentity): MirrorCallControlInvokeResult {
        val target = loadTarget.load(identity) ?: return MirrorCallControlInvokeResult.Gone
        val row = try {
            encode.encode(CallControlInvokeInput(target.canonId, target.callSessionId, target.callSequence, target.controlId, target.kind))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return MirrorCallControlInvokeResult.Failed
        }
        val invocation = ActionInvocation(
            target.controlId, target.canonId, target.kind.wire, target.callSequence, null,
            "PENDING", row.createdAt, row.expiresAt, row.createdAt,
        )
        val committed = try {
            commit.commit(invocation, row)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return MirrorCallControlInvokeResult.Failed
        }
        if (committed != ActionInvocationOutboxCommitResult.Committed) return MirrorCallControlInvokeResult.Failed
        runCatching(signalTransport)
        runCatching { scheduleExpiry.schedule(invocation.expiresAt) }
        runCatching { repost.repost(target) }
        return MirrorCallControlInvokeResult.Queued(target.controlId)
    }

    companion object {
        fun production(context: Context): MirrorCallControlInvoker {
            val app = context.applicationContext
            val dao = NotificationDb.get(app).reliableDeliveryDao()
            return MirrorCallControlInvoker(
                loadTarget = MirrorCallControlTargetLoader { identity ->
                    val canonId = dao.canonicalForMirrorIdentity(identity.mirrorTag, identity.mirrorId)
                        ?: return@MirrorCallControlTargetLoader null
                    val state = dao.canonical(canonId) ?: return@MirrorCallControlTargetLoader null
                    resolveMirrorCallControlTarget(identity, canonId, state)
                },
                encode = CallControlInvokeRowEncoder(CallControlEncoder(app)::encodeInvoke),
                commit = MirrorCallControlCommitter(dao::commitCallControlInvocationAndOutbound),
                signalTransport = { SyncService.notifyActionOutboxChanged(app) },
                scheduleExpiry = PersistentActionInvocationExpiryScheduler(app),
                repost = MirrorCallControlReposter { target ->
                    val state = dao.canonical(target.canonId)
                        ?.takeIf { it.state == "ACTIVE" && it.latestSequence == target.callSequence }
                        ?: return@MirrorCallControlReposter
                    DefaultAndroidNotificationPort(app, DeviceIdentity.getOrCreate(app), dao).postCallMirror(state)
                },
            )
        }
    }
}
