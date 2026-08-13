package co.twinotify.core.listener

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureCoordinatorTest {
    @Test
    fun commandsForSameCanonStayOrderedWhileDifferentCanonsRunConcurrently() = runTest {
        val persister = RecordingCapturePersister()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = persister,
            laneIdleMs = 100,
        )

        coordinator.submit(PostCommand("a", "ka", post("a")))
        coordinator.submit(PostCommand("b", "kb", post("b")))
        coordinator.submit(RemoveCommand("a", "ka", "app_cancel", 3_000))

        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), persister.sequencesFor("a"))
        assertEquals(listOf(1L), persister.sequencesFor("b"))
        assertTrue(persister.overlapped, "independent canonical lanes should not serialize globally")
    }

    @Test
    fun laneIsReclaimedAfterIdleAndBoundarySubmitIsNotDropped() = runTest {
        val persister = RecordingCapturePersister()
        val coordinator = CaptureCoordinator(
            scope = this,
            persister = persister,
            laneIdleMs = 50,
        )

        coordinator.submit(PostCommand("a", "ka", post("a")))
        runCurrent()
        advanceTimeBy(60)
        advanceUntilIdle()
        assertEquals(0, coordinator.activeLaneCountForTest())

        coordinator.submit(RemoveCommand("a", "ka", "app_cancel", 4_000))
        advanceUntilIdle()

        assertEquals(listOf(1L, 2L), persister.sequencesFor("a"))
        assertEquals(0, coordinator.activeLaneCountForTest())
    }

    @Test
    fun transientPersistenceFailureIsRetriedWithoutDroppingTheCommand() = runTest {
        var attempts = 0
        val persister = CapturePersister {
            attempts += 1
            if (attempts == 1) error("temporary Room failure")
            CapturePersistResult(sequence = 1)
        }
        val coordinator = CaptureCoordinator(this, persister, laneIdleMs = 50)

        coordinator.submit(PostCommand("retry", "key-retry", post("retry")))
        advanceUntilIdle()

        assertEquals(2, attempts)
    }

    @Test
    fun noPeerRetainsOnlyLatestStatePerCanonicalUntilPairingResumes() = runTest {
        var paired = false
        var persisted = 0
        val persister = CapturePersister {
            if (!paired) throw CaptureNotPairedException("no peer")
            persisted += 1
            CapturePersistResult(sequence = persisted.toLong())
        }
        val coordinator = CaptureCoordinator(this, persister, laneIdleMs = 50)

        coordinator.submit(PostCommand("offline", "key-offline", post("offline")))
        coordinator.submit(RemoveCommand("offline", "key-offline", "app_cancel", 5_000))
        advanceUntilIdle()

        assertEquals(1, coordinator.deferredCountForTest())
        paired = true
        coordinator.resumeDeferred()
        advanceUntilIdle()
        assertEquals(0, coordinator.deferredCountForTest())
        assertEquals(1, persisted, "only the latest command should be replayed after pairing")
    }

    private fun post(canonId: String) = SourceNotificationSnapshot(
        sourceKey = "key-$canonId",
        packageName = "example.$canonId",
        id = 1,
        tag = null,
        postTime = 1_000,
        flags = 0,
        category = null,
        visibility = 1,
        isGroupSummary = false,
        isOngoing = false,
        isClearable = true,
        appName = "example.$canonId",
        title = "title",
        text = "text",
        subText = null,
        bigText = null,
        smallIcon = null,
        largeIcon = null,
    )

    private class RecordingCapturePersister : CapturePersister {
        private val rows = CopyOnWriteArrayList<CapturedCommand>()
        @Volatile var overlapped = false

        override suspend fun persist(command: CaptureCommand): CapturePersistResult {
            val prior = rows.any { it.canonId != command.canonId && !it.finished }
            if (prior) overlapped = true
            val row = CapturedCommand(command.canonId, sequence = rows.count { it.canonId == command.canonId } + 1L)
            rows += row
            if (rows.size == 1) delay(10)
            row.finished = true
            return CapturePersistResult(sequence = row.sequence)
        }

        fun sequencesFor(canonId: String): List<Long> = rows.filter { it.canonId == canonId }.map { it.sequence }

        private data class CapturedCommand(val canonId: String, val sequence: Long, @Volatile var finished: Boolean = false)
    }
}
