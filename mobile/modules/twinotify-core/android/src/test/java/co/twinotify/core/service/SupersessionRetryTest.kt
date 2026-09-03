package co.twinotify.core.service

import co.twinotify.core.storage.SupersessionBundle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SupersessionRetryTest {
    @Test
    fun preparesAgainWhileTheCommitReportsARacedBundleThenSucceeds() = runTest {
        var prepared = 0
        val outcomes = ArrayDeque(listOf("unavailable", "unavailable", "committed"))
        val result = commitWithSupersessionRetry(
            prepare = { prepared += 1; SupersessionBundle(emptyList()) },
            commit = { outcomes.removeFirst() },
            isUnavailable = { it == "unavailable" },
        )
        assertEquals("committed", result)
        assertEquals(3, prepared)
    }

    @Test
    fun givesUpAfterBoundedAttemptsAndReturnsTheLastRacedResult() = runTest {
        var prepared = 0
        val result = commitWithSupersessionRetry(
            attempts = 3,
            prepare = { prepared += 1; SupersessionBundle(emptyList()) },
            commit = { "unavailable" },
            isUnavailable = { it == "unavailable" },
        )
        assertEquals("unavailable", result)
        assertEquals(3, prepared)
    }

    @Test
    fun unavailableReceiptPreparationStopsWithoutCommitting() = runTest {
        var committed = 0
        val result = commitWithSupersessionRetry<String>(
            prepare = { null },
            commit = { committed += 1; "committed" },
            isUnavailable = { false },
        )
        assertNull(result)
        assertEquals(0, committed)
    }

    @Test
    fun aNonRacedResultReturnsImmediately() = runTest {
        var prepared = 0
        val result = commitWithSupersessionRetry(
            prepare = { prepared += 1; SupersessionBundle(emptyList()) },
            commit = { "stale" },
            isUnavailable = { it == "unavailable" },
        )
        assertEquals("stale", result)
        assertEquals(1, prepared)
    }
}
