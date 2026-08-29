package co.twinotify.core.actions

import co.twinotify.core.storage.ActionExecution
import co.twinotify.core.storage.OutboundMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class ActionClaimRecoveryTest {
    @Test
    fun startupInsideGraceRearmsRemainingDeadline() = runTest {
        val store = FakeRecoveryStore(rows = listOf(claim(claimedAt = 1_000)))
        val scheduled = mutableListOf<Long>()

        val summary = recovery(store, now = 30_000, scheduled = scheduled).recover()

        assertEquals(ActionClaimRecoverySummary(finalized = 0, nextDueAt = 61_000), summary)
        assertEquals(listOf(61_000L), scheduled)
    }

    @Test
    fun startupAfterDeadlineFinalizesOutcomeUnknownAndSignalsTransport() = runTest {
        val store = FakeRecoveryStore(rows = listOf(claim(claimedAt = 1_000)))
        var signals = 0

        val summary = recovery(store, now = 61_000) { signals += 1 }.recover()

        assertEquals(ActionClaimRecoverySummary(finalized = 1, nextDueAt = null), summary)
        assertEquals(listOf("outcome_unknown"), store.completedStatuses)
        assertEquals(1, signals)
    }

    @Test
    fun multipleDueClaimsEachCommitExactlyOneResult() = runTest {
        val store = FakeRecoveryStore(
            rows = listOf(
                claim("11111111-1111-4111-8111-111111111111", claimedAt = 1_000),
                claim("22222222-2222-4222-8222-222222222222", claimedAt = 2_000),
            ),
        )

        assertEquals(2, recovery(store, now = 70_000).recover().finalized)
        assertEquals(2, store.resultRows.size)
        assertEquals(2, store.completedStatuses.size)
    }

    @Test
    fun racingRealCompletionSuppressesRecoveryResult() = runTest {
        val store = FakeRecoveryStore(
            rows = listOf(claim(claimedAt = 1_000)),
            completionWins = false,
        )
        var signals = 0

        assertEquals(0, recovery(store, now = 61_000) { signals += 1 }.recover().finalized)
        assertEquals(0, signals)
        assertEquals(emptyList(), store.resultRows)
    }

    private fun recovery(
        store: FakeRecoveryStore,
        now: Long,
        scheduled: MutableList<Long> = mutableListOf(),
        signal: () -> Unit = {},
    ) = ActionClaimRecovery(
        store = store,
        resultEncoder = ActionResultRowEncoder { input -> result(input.invocationId) },
        scheduler = ActionClaimWakeScheduler { scheduled += it },
        signalTransport = signal,
        clock = { now },
    )

    private class FakeRecoveryStore(
        rows: List<ActionExecution>,
        private val completionWins: Boolean = true,
    ) : ActionClaimRecoveryStore {
        private val claims = rows.toMutableList()
        val completedStatuses = mutableListOf<String>()
        val resultRows = mutableListOf<OutboundMessage>()

        override suspend fun dueClaims(cutoffClaimedAt: Long): List<ActionExecution> =
            claims.filter { it.claimedAt <= cutoffClaimedAt }

        override suspend fun earliestClaimedAt(): Long? = claims.minOfOrNull { it.claimedAt }

        override suspend fun completeOutcomeUnknown(
            execution: ActionExecution,
            now: Long,
            result: OutboundMessage,
        ): Boolean {
            if (!completionWins || !claims.remove(execution)) return false
            completedStatuses += "outcome_unknown"
            resultRows += result
            return true
        }
    }

    private fun claim(
        id: String = "11111111-1111-4111-8111-111111111111",
        claimedAt: Long,
    ) = ActionExecution(
        invocationId = id,
        canonId = "origin:pkg:1:tag",
        actionId = "33333333-3333-4333-8333-333333333333",
        state = "CLAIMED",
        resultStatus = null,
        claimedAt = claimedAt,
        completedAt = null,
    )

    private fun result(id: String) = OutboundMessage(
        msgId = id,
        canonId = null,
        sequence = null,
        eventType = "notif.action.result",
        protocolVersion = 2,
        envelopeJson = "{}",
        envelopeSha256 = "a".repeat(64),
        byteSize = 2,
        createdAt = 1,
        expiresAt = 600_001,
        custodyAcceptedAt = null,
        custodyRoute = null,
        attempts = 0,
        nextAttemptAt = 1,
        state = "NEW",
        lastError = null,
        requiresPeerReceipt = false,
    )
}
