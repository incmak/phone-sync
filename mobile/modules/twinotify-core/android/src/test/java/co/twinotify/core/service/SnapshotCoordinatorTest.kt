package co.twinotify.core.service

import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.SnapshotBeginResult
import co.twinotify.core.storage.SnapshotCommitResult
import co.twinotify.core.storage.SnapshotStage
import co.twinotify.core.storage.SnapshotStageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SnapshotCoordinatorTest {
    @Test
    fun incompleteSnapshotNeverMutatesExistingState() = runTest {
        val store = FakeSnapshotStore(
            states = mutableListOf(state("existing", 3)),
            commitResult = SnapshotCommitResult.Incomplete(expected = 1, staged = 0),
        )
        val coordinator = SnapshotCoordinator(store, clock = { 20_000L })

        coordinator.onBegin(SnapshotBeginEvent("s1", ORIGIN, 1))
        coordinator.onItem(SnapshotItemEvent("s1", ORIGIN, "new", 4, payload("new")))
        val result = coordinator.onEnd(SnapshotEndEvent("s1", ORIGIN, digestOf("new", 4)))

        assertEquals(SnapshotConvergence.Incomplete(1, 0), result)
        assertEquals(listOf("existing"), store.states.map { it.canonId })
    }

    @Test
    fun digestMismatchDoesNotCommitOrDeleteStaging() = runTest {
        val store = FakeSnapshotStore(
            commitResult = SnapshotCommitResult.DigestMismatch("a".repeat(64), "b".repeat(64)),
        )
        val coordinator = SnapshotCoordinator(store)
        coordinator.onBegin(SnapshotBeginEvent("s1", ORIGIN, 0))

        val result = coordinator.onEnd(SnapshotEndEvent("s1", ORIGIN, "a".repeat(64)))

        assertIs<SnapshotConvergence.DigestMismatch>(result)
        assertEquals(1, store.commitCount)
    }

    @Test
    fun validEndReturnsAtomicCommitResult() = runTest {
        val store = FakeSnapshotStore(
            commitResult = SnapshotCommitResult.Committed(upserted = 1, cancelled = 1),
        )
        val coordinator = SnapshotCoordinator(store)
        coordinator.onBegin(SnapshotBeginEvent("s1", ORIGIN, 0))

        val result = coordinator.onEnd(SnapshotEndEvent("s1", ORIGIN, "c".repeat(64)))

        assertEquals(SnapshotConvergence.Committed(1, 1), result)
        assertEquals(1, store.commitCount)
    }

    @Test
    fun expiredStagingIsSweptBeforeNewBegin() = runTest {
        val store = FakeSnapshotStore()
        val coordinator = SnapshotCoordinator(store, clock = { 600_000L })
        coordinator.onBegin(SnapshotBeginEvent("s2", ORIGIN, 0))

        assertEquals(1, store.expireCount)
        assertEquals(600_000L - SnapshotCoordinator.SNAPSHOT_TTL_MS, store.lastExpiryCutoff)
    }

    @Test
    fun duplicateBeginIsIdempotentAndDoesNotEraseStagedRows() = runTest {
        val store = FakeSnapshotStore()
        val coordinator = SnapshotCoordinator(store)
        coordinator.onBegin(SnapshotBeginEvent("same", ORIGIN, 1))
        coordinator.onItem(SnapshotItemEvent("same", ORIGIN, "canon", 1, payload("canon")))
        coordinator.onBegin(SnapshotBeginEvent("same", ORIGIN, 1))

        assertEquals(1, store.beginCount)
        assertEquals(1, store.stagedCount)
    }

    private class FakeSnapshotStore(
        val states: MutableList<CanonicalNotificationState> = mutableListOf(),
        private val commitResult: SnapshotCommitResult = SnapshotCommitResult.Committed(0, 0),
    ) : SnapshotStore {
        var commitCount = 0
        var beginCount = 0
        var stagedCount = 0
        var expireCount = 0
        var lastExpiryCutoff = 0L
        private val begun = mutableSetOf<String>()

        override suspend fun activeOriginStates(originDevice: String): List<CanonicalNotificationState> =
            states.filter { it.originDevice == originDevice && it.state == "ACTIVE" }

        override suspend fun beginSnapshot(
            snapshotId: String,
            originDevice: String,
            expectedItemCount: Int,
            receivedAt: Long,
        ): SnapshotBeginResult {
            if (snapshotId !in begun) {
                beginCount += 1
                begun += snapshotId
            }
            return SnapshotBeginResult.Started(0)
        }

        override suspend fun stageSnapshotItem(row: SnapshotStage, expectedOriginDevice: String): SnapshotStageResult =
            stageSnapshotItem(row)

        override suspend fun stageSnapshotItem(row: SnapshotStage): SnapshotStageResult =
            if (row.snapshotId in begun) {
                stagedCount += 1
                SnapshotStageResult.Staged
            } else SnapshotStageResult.MissingBegin

        override suspend fun commitSnapshot(
            snapshotId: String,
            expectedDigest: String,
            committedAt: Long,
        ): SnapshotCommitResult {
            commitCount += 1
            return commitResult
        }

        override suspend fun expireSnapshotStages(cutoff: Long): Int {
            expireCount += 1
            lastExpiryCutoff = cutoff
            return 0
        }
    }

    private fun state(canonId: String, sequence: Long) = CanonicalNotificationState(
        canonId = canonId,
        originDevice = ORIGIN,
        latestSequence = sequence,
        state = "ACTIVE",
        desiredPayloadJson = payload(canonId),
        materializedSequence = sequence,
        sourceNotificationKey = "source:$canonId",
        mirrorLocalId = null,
        mirrorLocalTag = null,
        peerCancelPending = false,
        updatedAt = sequence,
    )

    private fun payload(canonId: String) = """{"type":"notif.post","canon_id":"$canonId","package_name":"pkg","id":1,"visibility":"private"}"""

    private fun digestOf(canonId: String, sequence: Long): String {
        val bytes = "$canonId\u0000$sequence\u0000ACTIVE".toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    companion object {
        private const val ORIGIN = "origin-device"
    }
}
