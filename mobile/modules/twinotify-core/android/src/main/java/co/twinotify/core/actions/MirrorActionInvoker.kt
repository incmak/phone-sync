package co.twinotify.core.actions

import android.app.KeyguardManager
import android.content.Context
import co.twinotify.core.listener.NotifActionJson
import co.twinotify.core.listener.NotifPostJson
import co.twinotify.core.service.DefaultAndroidNotificationPort
import co.twinotify.core.service.SyncService
import co.twinotify.core.storage.ActionInvocation
import co.twinotify.core.storage.ActionInvocationOutboxCommitResult
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.DeviceIdentity
import co.twinotify.core.storage.NotificationDb
import co.twinotify.core.storage.OutboundMessage
import java.util.UUID
import kotlinx.coroutines.CancellationException

data class MirrorActionTarget(
    val canonId: String,
    val notificationSequence: Long,
    val localTag: String,
    val localId: Int,
    val action: NotifActionJson,
)

internal fun resolveMirrorActionTarget(
    identity: ActionInvokeIdentity,
    canonId: String,
    state: CanonicalNotificationState,
    advertised: MirrorActionTarget?,
): MirrorActionTarget? {
    if (state.mirrorLocalTag != identity.mirrorTag || state.mirrorLocalId != identity.mirrorId) return null
    advertised
        ?.takeIf { it.canonId == canonId }
        ?.let { return it }
    if (state.state != "ACTIVE") return null
    val post = state.desiredPayloadJson?.let { raw ->
        runCatching { NotifPostJson.fromPayloadJson(raw) }.getOrNull()
    } ?: return null
    if (post.canon_id != canonId) return null
    val action = post.actions.firstOrNull { it.action_id == identity.actionId } ?: return null
    return MirrorActionTarget(canonId, state.latestSequence, identity.mirrorTag, identity.mirrorId, action)
}

fun interface MirrorActionTargetLoader {
    suspend fun load(identity: ActionInvokeIdentity): MirrorActionTarget?
}

fun interface ActionInvokeRowEncoder {
    suspend fun encode(input: ActionInvokeInput): OutboundMessage
}

fun interface MirrorActionCommitter {
    suspend fun commit(
        invocation: ActionInvocation,
        invoke: OutboundMessage,
    ): ActionInvocationOutboxCommitResult
}

fun interface ActionInvocationExpiryScheduler {
    fun schedule(dueAt: Long)
}

fun interface MirrorActionReposter {
    suspend fun repost(target: MirrorActionTarget)
}

sealed interface MirrorActionInvokeResult {
    data class Queued(val invocationId: String) : MirrorActionInvokeResult
    data object Locked : MirrorActionInvokeResult
    data object Gone : MirrorActionInvokeResult
    data object InvalidReply : MirrorActionInvokeResult
    data object Failed : MirrorActionInvokeResult
}

class MirrorActionInvoker(
    private val isDeviceLocked: () -> Boolean,
    private val loadTarget: MirrorActionTargetLoader,
    private val encode: ActionInvokeRowEncoder,
    private val commit: MirrorActionCommitter,
    private val signalTransport: () -> Unit,
    private val scheduleExpiry: ActionInvocationExpiryScheduler,
    private val repost: MirrorActionReposter,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun invoke(identity: ActionInvokeIdentity, replyText: String?): MirrorActionInvokeResult {
        if (isDeviceLocked()) return MirrorActionInvokeResult.Locked
        val target = loadTarget.load(identity) ?: return MirrorActionInvokeResult.Gone
        if (!validReply(target.action, replyText)) return MirrorActionInvokeResult.InvalidReply

        val invocationId = newId()
        val row = try {
            encode.encode(
                ActionInvokeInput(
                    invocationId = invocationId,
                    canonId = target.canonId,
                    actionId = target.action.action_id,
                    notificationSequence = target.notificationSequence,
                    replyText = replyText,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return MirrorActionInvokeResult.Failed
        }
        val invocation = ActionInvocation(
            invocationId = invocationId,
            canonId = target.canonId,
            actionId = target.action.action_id,
            notificationSequence = target.notificationSequence,
            replyText = replyText,
            state = "PENDING",
            createdAt = row.createdAt,
            expiresAt = row.expiresAt,
            updatedAt = row.createdAt,
        )
        val result = try {
            commit.commit(invocation, row)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            return MirrorActionInvokeResult.Failed
        }
        if (result != ActionInvocationOutboxCommitResult.Committed) return MirrorActionInvokeResult.Failed

        runCatching(signalTransport)
        runCatching { scheduleExpiry.schedule(invocation.expiresAt) }
        runCatching { repost.repost(target) }
        return MirrorActionInvokeResult.Queued(invocationId)
    }

    private fun validReply(action: NotifActionJson, replyText: String?): Boolean {
        if (action.reply != (replyText != null)) return false
        return replyText == null || replyText.toByteArray(Charsets.UTF_8).size <= MAX_REPLY_BYTES
    }

    companion object {
        private const val MAX_REPLY_BYTES = 4_096

        fun production(context: Context): MirrorActionInvoker {
            val app = context.applicationContext
            val dao = NotificationDb.get(app).reliableDeliveryDao()
            return MirrorActionInvoker(
                isDeviceLocked = {
                    app.getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false
                },
                loadTarget = MirrorActionTargetLoader { identity ->
                    val canonId = dao.canonicalForMirrorIdentity(identity.mirrorTag, identity.mirrorId)
                        ?: return@MirrorActionTargetLoader null
                    val state = dao.canonical(canonId)
                        ?: return@MirrorActionTargetLoader null
                    resolveMirrorActionTarget(
                        identity,
                        canonId,
                        state,
                        ProcessMirrorAdvertisedActions.lookup(identity),
                    )
                },
                encode = ActionInvokeRowEncoder(ActionControlEncoder(app)::encodeInvoke),
                commit = MirrorActionCommitter(dao::commitActionInvocationAndOutbound),
                signalTransport = { SyncService.notifyActionOutboxChanged(app) },
                scheduleExpiry = PersistentActionInvocationExpiryScheduler(app),
                repost = MirrorActionReposter { target ->
                    val state = dao.canonical(target.canonId)
                        ?.takeIf { it.state == "ACTIVE" && it.latestSequence == target.notificationSequence }
                        ?: return@MirrorActionReposter
                    val localDevice = DeviceIdentity.getOrCreate(app)
                    DefaultAndroidNotificationPort(app, localDevice, dao).postMirror(state)
                },
            )
        }
    }
}
