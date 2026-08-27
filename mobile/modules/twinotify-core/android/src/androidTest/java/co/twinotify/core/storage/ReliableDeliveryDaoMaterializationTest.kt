package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlin.test.assertEquals
import org.junit.Test

class ReliableDeliveryDaoMaterializationTest {
    @Test
    fun newerDesiredSequenceBypassesOlderParkedRetry() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reliableDeliveryDao()
            dao.putCanonical(state(sequence = 2, materialized = 0))
            dao.putMaterializationRetry(
                MaterializationRetry(
                    canonId = "canon",
                    sequence = 1,
                    nextAttemptAt = null,
                    attempts = 1,
                    disposition = MaterializationRetryDisposition.PERMISSION_BLOCKED,
                    lastError = "post_permission_blocked",
                ),
            )

            assertEquals(listOf(2L), dao.pendingMaterialization(now = 1_000).map { it.latestSequence })
        } finally {
            db.close()
        }
    }

    @Test
    fun routineQueryExcludesPermissionBlockedSameSequenceWithoutADueTime() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reliableDeliveryDao()
            dao.putCanonical(state(sequence = 2, materialized = 0))
            dao.putMaterializationRetry(
                MaterializationRetry(
                    canonId = "canon",
                    sequence = 2,
                    nextAttemptAt = null,
                    attempts = 1,
                    disposition = MaterializationRetryDisposition.PERMISSION_BLOCKED,
                    lastError = "post_permission_blocked",
                ),
            )

            assertEquals(emptyList(), dao.pendingMaterialization(now = 1_000).map { it.latestSequence })
            assertEquals(
                listOf(2L),
                dao.pendingMaterialization(now = 1_000, includePermissionBlocked = true).map { it.latestSequence },
            )
            assertEquals(null, dao.earliestRetryableMaterializationAt())
        } finally {
            db.close()
        }
    }

    @Test
    fun clearingOlderAttemptDoesNotDeleteNewerRetryRow() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reliableDeliveryDao()
            dao.putMaterializationRetry(
                MaterializationRetry(
                    canonId = "canon",
                    sequence = 3,
                    nextAttemptAt = 10_000,
                    attempts = 1,
                    disposition = MaterializationRetryDisposition.RETRYABLE,
                    lastError = "platform_retryable",
                ),
            )
            dao.clearMaterializationRetry("canon", sequence = 2)

            assertEquals(3L, requireNotNull(dao.materializationRetry("canon")).sequence)
        } finally {
            db.close()
        }
    }

    @Test
    fun completingNewerSequenceClearsOlderRetryButPreservesNewerRetry() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reliableDeliveryDao()
            dao.putMaterializationRetry(retry(sequence = 1))
            dao.clearMaterializationRetriesThrough("canon", sequence = 2)
            assertEquals(null, dao.materializationRetry("canon"))

            dao.putMaterializationRetry(retry(sequence = 3))
            dao.clearMaterializationRetriesThrough("canon", sequence = 2)
            assertEquals(3L, requireNotNull(dao.materializationRetry("canon")).sequence)
        } finally {
            db.close()
        }
    }

    @Test
    fun earliestRetryIgnoresOrphanAndStaleRows() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reliableDeliveryDao()
            dao.putMaterializationRetry(retry(sequence = 1, dueAt = 1))
            dao.putCanonical(state(sequence = 2, materialized = 0))
            assertEquals(null, dao.earliestRetryableMaterializationAt())

            dao.putMaterializationRetry(retry(sequence = 2, dueAt = 20))
            assertEquals(20L, dao.earliestRetryableMaterializationAt())
        } finally {
            db.close()
        }
    }

    @Test
    fun activePeerMirrorStatesExcludesLocalRowsUnmappedRowsAndCalls() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val dao = db.reliableDeliveryDao()
            dao.putCanonical(state(canonId = "peer-live", originDevice = "peer", mirrorId = 9, mirrorTag = "mirror"))
            dao.putCanonical(state(canonId = "local", originDevice = "local", mirrorId = 10, mirrorTag = "local"))
            dao.putCanonical(state(canonId = "peer-no-mirror", originDevice = "peer", mirrorId = null, mirrorTag = null))
            dao.putCanonical(state(canonId = "call:peer-call", originDevice = "peer", mirrorId = 11, mirrorTag = "call"))

            assertEquals(listOf("peer-live"), dao.activePeerMirrorStates("local").map { it.canonId })
        } finally {
            db.close()
        }
    }

    private fun retry(sequence: Long, dueAt: Long = 10_000) = MaterializationRetry(
        canonId = "canon",
        sequence = sequence,
        nextAttemptAt = dueAt,
        attempts = 1,
        disposition = MaterializationRetryDisposition.RETRYABLE,
        lastError = "platform_retryable",
    )

    private fun state(
        sequence: Long = 2,
        materialized: Long = 0,
        canonId: String = "canon",
        originDevice: String = "peer",
        mirrorId: Int? = 1,
        mirrorTag: String? = "tag",
    ) = CanonicalNotificationState(
        canonId = canonId,
        originDevice = originDevice,
        latestSequence = sequence,
        state = "ACTIVE",
        desiredPayloadJson = "{}",
        materializedSequence = materialized,
        sourceNotificationKey = null,
        mirrorLocalId = mirrorId,
        mirrorLocalTag = mirrorTag,
        peerCancelPending = false,
        updatedAt = 1_000,
    )
}
