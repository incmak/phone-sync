package co.twinotify.core.listener

interface OutboundSink {
    suspend fun enqueuePost(post: NotifPostJson)
    suspend fun enqueueCancel(canonId: String, reason: String, originDevice: String, tsMs: Long)
    suspend fun enqueueUnpair(reason: String, originDevice: String, tsMs: Long)
}

/** Application-scoped durable adapter retained for non-listener callers (tap/unpair actions). */
class DurableOutboundSink private constructor(context: android.content.Context) : OutboundSink {
    private val appContext = context.applicationContext
    private val coordinator = CaptureCoordinator.get(appContext)
    private val persister = DurableCapturePersister(appContext)

    override suspend fun enqueuePost(post: NotifPostJson) {
        check(coordinator.submit(PostCommand(
            canonId = post.canon_id,
            sourceKey = "",
            snapshot = SourceNotificationSnapshot.fromPost(post),
        ))) { "durable capture lane rejected notification post" }
    }

    override suspend fun enqueueCancel(canonId: String, reason: String, originDevice: String, tsMs: Long) {
        check(coordinator.submit(RemoveCommand(canonId, "", reason, tsMs))) {
            "durable capture lane rejected notification cancel"
        }
    }

    override suspend fun enqueueUnpair(reason: String, originDevice: String, tsMs: Long) {
        persister.persistUnpair(reason, originDevice, tsMs)
    }

    companion object {
        @Volatile private var instance: DurableOutboundSink? = null

        fun get(context: android.content.Context): DurableOutboundSink = instance ?: synchronized(this) {
            instance ?: DurableOutboundSink(context.applicationContext).also { instance = it }
        }
    }
}
