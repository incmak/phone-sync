package co.twinotify.core.service

import co.twinotify.core.listener.CanonIdBuilder
import co.twinotify.core.listener.SourceNotificationSnapshot
import co.twinotify.core.storage.CanonicalNotificationState
import co.twinotify.core.storage.SnapshotBeginResult
import co.twinotify.core.storage.SnapshotCommitResult
import co.twinotify.core.storage.SnapshotStage
import co.twinotify.core.storage.SnapshotStageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SnapshotCoordinatorTest {
    @Test
    fun localDigestExcludesActiveCallStateFromNotificationSummary() = runTest {
        val notificationCanon = notificationCanon()
        val store = FakeSnapshotStore(
            states = mutableListOf(
                state(notificationCanon, 4),
                callState(sequence = 8),
            ),
        )

        val digest = SnapshotCoordinator(store).localDigest(ORIGIN)

        assertEquals(1, digest.count)
        assertEquals(digestOf(notificationCanon, 4), digest.digest)
    }

    @Test
    fun repairEnumerationIgnoresActiveCallState() = runTest {
        val snapshot = sourceSnapshot()
        val notificationCanon = notificationCanon(snapshot)
        val store = FakeSnapshotStore(
            states = mutableListOf(
                state(notificationCanon, 4),
                callState(sequence = 8),
            ),
        )
        val emitted = mutableListOf<Any>()
        val source = object : SnapshotSource {
            override fun active(originDevice: String): List<SourceNotificationSnapshot> = listOf(snapshot)

            override fun payloadJson(originDevice: String, snapshot: SourceNotificationSnapshot): String =
                payload(notificationCanon)
        }
        val coordinator = SnapshotCoordinator(
            store = store,
            emitter = SnapshotEmitter { emitted += it },
            clock = { 10_000L },
            source = source,
            localOriginDevice = ORIGIN,
        )

        val result = coordinator.onDigest(
            StateDigest(ORIGIN, count = 0, digest = emptyDigest()),
            force = true,
        )

        val started = assertIs<SnapshotConvergence.RepairStarted>(result)
        assertEquals(1, started.itemCount)
        assertEquals(listOf(notificationCanon), emitted.filterIsInstance<SnapshotItemEvent>().map { it.canonId })
    }

    @Test
    fun repairSnapshotReusesCommittedPayloadVerbatim() = runTest {
        val snapshot = sourceSnapshot()
        val canon = notificationCanon(snapshot)
        val committedPayload = payload(canon).replace("}", ",\"actions\":[{\"action_id\":\"stable\"}]}")
        val store = FakeSnapshotStore(
            states = mutableListOf(state(canon, 9).copy(desiredPayloadJson = committedPayload)),
        )
        val emitted = mutableListOf<Any>()
        val source = object : SnapshotSource {
            override fun active(originDevice: String): List<SourceNotificationSnapshot> = listOf(snapshot)
            override fun payloadJson(originDevice: String, snapshot: SourceNotificationSnapshot): String =
                error("snapshot must not rebuild a committed payload")
        }
        val coordinator = SnapshotCoordinator(
            store = store,
            emitter = SnapshotEmitter { emitted += it },
            clock = { 10_000L },
            source = source,
            localOriginDevice = ORIGIN,
        )

        val result = coordinator.onDigest(StateDigest(ORIGIN, 0, emptyDigest()), force = true)

        assertIs<SnapshotConvergence.RepairStarted>(result)
        assertEquals(committedPayload, emitted.filterIsInstance<SnapshotItemEvent>().single().payloadJson)
    }

    @Test
    fun callOnlyOriginHasEmptyNotificationDigest() = runTest {
        val store = FakeSnapshotStore(states = mutableListOf(callState(sequence = 3)))

        val digest = SnapshotCoordinator(store).localDigest(ORIGIN)

        assertEquals(0, digest.count)
        assertEquals(emptyDigest(), digest.digest)
    }

    @Test
    fun callNamespaceSnapshotItemIsRejected() = runTest {
        val coordinator = SnapshotCoordinator(FakeSnapshotStore())
        coordinator.onBegin(SnapshotBeginEvent("call-item", ORIGIN, 1))

        assertFailsWith<IllegalArgumentException> {
            coordinator.onItem(
                SnapshotItemEvent(
                    snapshotId = "call-item",
                    originDevice = ORIGIN,
                    canonId = CALL_CANON_ID,
                    sequence = 2,
                    payloadJson = payload(CALL_CANON_ID),
                ),
            )
        }
    }

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

    private fun callState(sequence: Long) = state(CALL_CANON_ID, sequence).copy(
        desiredPayloadJson = """{"call_session_id":"11111111-1111-4111-8111-111111111111","state":"ringing","direction":"incoming"}""",
        sourceNotificationKey = null,
    )

    private fun sourceSnapshot() = SourceNotificationSnapshot(
        sourceKey = "source-key",
        packageName = "example.notifications",
        id = 7,
        tag = "thread",
        postTime = 1_000,
        flags = 0,
        category = null,
        visibility = 1,
        isGroupSummary = false,
        isOngoing = false,
        isClearable = true,
        appName = "Example",
        title = "Title",
        text = "Body",
        subText = null,
        bigText = null,
        smallIcon = null,
        largeIcon = null,
    )

    private fun notificationCanon(snapshot: SourceNotificationSnapshot = sourceSnapshot()): String =
        CanonIdBuilder.build(ORIGIN, snapshot.packageName, snapshot.id, snapshot.tag)

    private fun payload(canonId: String) = """{"type":"notif.post","canon_id":"$canonId","package_name":"pkg","id":1,"visibility":"private"}"""

    private fun digestOf(canonId: String, sequence: Long): String {
        val bytes = "$canonId\u0000$sequence\u0000ACTIVE".toByteArray()
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun emptyDigest(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(ByteArray(0))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        private const val ORIGIN = "origin-device"
        private const val CALL_CANON_ID = "call:11111111-1111-4111-8111-111111111111"
    }
}
