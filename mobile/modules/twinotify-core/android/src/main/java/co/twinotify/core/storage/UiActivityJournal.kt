package co.twinotify.core.storage

import java.util.UUID

enum class UiActivityDirection { SENT, RECEIVED }

enum class UiActivityKind { NOTIFICATION, DISMISSAL, CALL }

enum class UiActivityStatus { QUEUED, APPLIED, DELIVERED, DISMISSED, EXPIRED, FAILED }

interface UiActivityStore {
    suspend fun upsertUiActivity(row: UiActivityEvent)
    suspend fun uiActivityForMessage(msgId: String): UiActivityEvent?
    suspend fun recentUiActivity(limit: Int): List<UiActivityEvent>
    suspend fun deleteUiActivityBefore(cutoff: Long): Int
    suspend fun trimUiActivityToLimit(limit: Int): Int
}

class UiActivityJournal(
    private val store: UiActivityStore,
    private val eventId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun recordQueued(
        msgId: String,
        packageName: String?,
        appName: String?,
        kind: UiActivityKind,
        now: Long,
    ) = record(
        msgId = msgId,
        packageName = packageName,
        appName = appName,
        direction = UiActivityDirection.SENT,
        kind = kind,
        status = UiActivityStatus.QUEUED,
        now = now,
    )

    suspend fun recordApplied(
        msgId: String,
        packageName: String?,
        appName: String?,
        kind: UiActivityKind,
        now: Long,
    ) = record(
        msgId = msgId,
        packageName = packageName,
        appName = appName,
        direction = UiActivityDirection.RECEIVED,
        kind = kind,
        status = UiActivityStatus.APPLIED,
        now = now,
    )

    suspend fun markTerminal(
        msgId: String,
        status: UiActivityStatus,
        route: String?,
        now: Long,
    ) {
        val existing = store.uiActivityForMessage(msgId) ?: return
        store.upsertUiActivity(existing.copy(status = status.name, route = route, occurredAt = now))
        maintain(now)
    }

    suspend fun recent(limit: Int): List<UiActivityEvent> =
        store.recentUiActivity(limit.coerceIn(MIN_RECENT_LIMIT, MAX_RECENT_LIMIT))

    private suspend fun record(
        msgId: String,
        packageName: String?,
        appName: String?,
        direction: UiActivityDirection,
        kind: UiActivityKind,
        status: UiActivityStatus,
        now: Long,
    ) {
        val existing = store.uiActivityForMessage(msgId)
        store.upsertUiActivity(
            UiActivityEvent(
                eventId = existing?.eventId ?: eventId(),
                msgId = msgId,
                packageName = packageName ?: existing?.packageName,
                appName = appName ?: existing?.appName,
                direction = direction.name,
                kind = kind.name,
                status = status.name,
                route = existing?.route,
                occurredAt = now,
            ),
        )
        maintain(now)
    }

    private suspend fun maintain(now: Long) {
        store.deleteUiActivityBefore(now - RETENTION_MS)
        store.trimUiActivityToLimit(MAX_ROWS)
    }

    companion object {
        const val MAX_ROWS = 500
        const val MAX_RECENT_LIMIT = 20
        private const val MIN_RECENT_LIMIT = 1
        private const val RETENTION_MS = 30L * 24L * 60L * 60L * 1_000L
    }
}
