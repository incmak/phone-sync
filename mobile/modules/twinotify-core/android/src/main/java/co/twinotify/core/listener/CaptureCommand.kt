package co.twinotify.core.listener

import android.graphics.drawable.Icon

/**
 * Immutable input captured at the notification-listener boundary.  In particular, this type
 * never carries a StatusBarNotification: framework objects are mutable and are only valid for
 * the duration of a callback.
 */
data class SourceNotificationSnapshot(
    val sourceKey: String,
    val packageName: String,
    val id: Int,
    val tag: String?,
    val postTime: Long,
    val flags: Int,
    val category: String?,
    val visibility: Int,
    val isGroupSummary: Boolean,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val appName: String?,
    val title: String?,
    val text: String?,
    val subText: String?,
    val bigText: String?,
    val smallIcon: Icon?,
    val largeIcon: Icon?,
    /** Already-encoded icons are used by compatibility callers that already built a payload. */
    val smallIconPngB64: String? = null,
    val largeIconPngB64: String? = null,
) {
    companion object {
        fun fromPost(post: NotifPostJson): SourceNotificationSnapshot = SourceNotificationSnapshot(
            sourceKey = "",
            packageName = post.package_name,
            id = post.id,
            tag = post.tag,
            postTime = post.ts,
            flags = 0,
            category = null,
            visibility = when (post.visibility) {
                "public" -> 1
                "secret" -> -1
                else -> 0
            },
            isGroupSummary = post.is_group_summary,
            isOngoing = post.is_ongoing,
            isClearable = post.is_clearable,
            appName = post.app_name,
            title = post.title,
            text = post.text,
            subText = post.sub_text,
            bigText = post.big_text,
            smallIcon = null,
            largeIcon = null,
            smallIconPngB64 = post.small_icon_png_b64,
            largeIconPngB64 = post.large_icon_png_b64,
        )
    }
}

sealed interface CaptureCommand {
    val canonId: String
    val sourceKey: String
}

data class PostCommand(
    override val canonId: String,
    override val sourceKey: String,
    val snapshot: SourceNotificationSnapshot,
) : CaptureCommand

data class RemoveCommand(
    override val canonId: String,
    override val sourceKey: String,
    val reason: String,
    val removedAt: Long,
) : CaptureCommand

data class CapturePersistResult(
    val sequence: Long,
    val msgId: String? = null,
)

/** Durable boundary used by the ordered coordinator and replaceable by deterministic test fakes. */
fun interface CapturePersister {
    suspend fun persist(command: CaptureCommand): CapturePersistResult
}
