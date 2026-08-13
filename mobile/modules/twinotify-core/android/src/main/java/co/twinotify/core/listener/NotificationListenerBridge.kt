package co.twinotify.core.listener

import android.service.notification.NotificationListenerService
import java.util.Collections

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

    internal fun attachedForTest(): TwinotifyNotificationListener? = synchronized(lock) { attached }
}
