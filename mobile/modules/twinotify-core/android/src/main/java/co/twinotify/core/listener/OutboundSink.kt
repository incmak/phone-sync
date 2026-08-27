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
        requireDurableAdmission(coordinator.submitDurably(PostCommand(
            canonId = post.canon_id,
            sourceKey = "",
            snapshot = SourceNotificationSnapshot.fromPost(post),
        )))
    }

    override suspend fun enqueueCancel(canonId: String, reason: String, originDevice: String, tsMs: Long) {
        requireDurableAdmission(coordinator.submitDurably(RemoveCommand(canonId, "", reason, tsMs)))
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

private fun requireDurableAdmission(admission: CaptureAdmission) {
    check(admission == CaptureAdmission.Accepted) { "durable_capture_admission_closed" }
}
