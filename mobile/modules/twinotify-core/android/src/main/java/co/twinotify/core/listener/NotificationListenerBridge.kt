package co.twinotify.core.listener

import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.Collections

internal fun shouldCaptureOutbound(sourcePackage: String, selfPackage: String): Boolean =
    sourcePackage != selfPackage

/** Lifecycle-safe bridge for platform operations that require a bound notification listener. */
object NotificationListenerBridge {
    private val lock = Any()
    private var attached: TwinotifyNotificationListener? = null

    fun attach(listener: TwinotifyNotificationListener) {
        synchronized(lock) { attached = listener }
    }

    fun detach(listener: TwinotifyNotificationListener) {
        synchronized(lock) {
            if (attached === listener) attached = null
        }
    }

    /** Returns false while Android has not bound the listener service. */
    fun cancelSource(exactKey: String): Boolean {
        require(exactKey.isNotEmpty()) { "source notification key must not be empty" }
        val listener = synchronized(lock) { attached } ?: return false
        return runCatching {
            listener.cancelNotification(exactKey)
            true
        }.getOrDefault(false)
    }

    /** Snapshot of exact platform keys visible to the currently bound service. */
    fun activeSources(): Set<String> {
        val listener = synchronized(lock) { attached } ?: return emptySet()
        return runCatching {
            listener.activeNotifications.orEmpty().mapTo(mutableSetOf()) { it.key }
        }.getOrDefault(emptySet())
    }

    /**
     * Returns a point-in-time copy of active framework notifications for snapshot repair. The
     * returned objects are consumed immediately by [NotifPostBuilder.captureSnapshot]; callers
     * must not retain framework instances beyond this operation.
     */
    fun activeNotifications(): List<StatusBarNotification> {
        val listener = synchronized(lock) { attached } ?: return emptyList()
        return runCatching { listener.activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
    }

    fun isAttached(): Boolean = synchronized(lock) { attached != null }

    /** Captures immutable payloads while the listener is bound, excluding this app and denylisted packages. */
    fun activeSourceSnapshots(
        context: Context,
        denylist: Set<String> = emptySet(),
        selfPackage: String = context.packageName,
    ): List<SourceNotificationSnapshot> = activeNotifications().asSequence()
        .filter { shouldCaptureOutbound(it.packageName, selfPackage) }
        .mapNotNull { NotifPostBuilder.captureSnapshot(it, context, denylist) }
        .toList()

    /** Captures source payloads and this package's live mirrors from one bound-listener read. */
    internal fun activeCaptureSnapshot(
        context: Context,
        denylist: Set<String> = emptySet(),
        selfPackage: String = context.packageName,
    ): ListenerCaptureSnapshot {
        val notifications = activeNotifications()
        return ListenerCaptureSnapshot(
            sourceSnapshots = notifications.asSequence()
                .filter { shouldCaptureOutbound(it.packageName, selfPackage) }
                .mapNotNull { NotifPostBuilder.captureSnapshot(it, context, denylist) }
                .toList(),
            liveMirrorIdentities = notifications.asSequence()
                .filter { it.packageName == selfPackage }
                .mapTo(hashSetOf()) { MirrorNotificationIdentity(it.tag.orEmpty(), it.id) },
        )
    }

    internal fun attachedForTest(): TwinotifyNotificationListener? = synchronized(lock) { attached }
}

internal data class ListenerCaptureSnapshot(
    val sourceSnapshots: List<SourceNotificationSnapshot>,
    val liveMirrorIdentities: Set<MirrorNotificationIdentity>,
)
