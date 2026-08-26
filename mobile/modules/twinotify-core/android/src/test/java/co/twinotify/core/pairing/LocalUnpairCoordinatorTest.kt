package co.twinotify.core.pairing

import co.twinotify.core.service.CustodyRoute
import co.twinotify.core.service.PreparedLocalUnpairService
import co.twinotify.core.service.UnpairCustodyTracker
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class LocalUnpairCoordinatorTest {
    @Test
    fun lanCustodyReleasesTeardownInExactOrder() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val coordinator = coordinator(
            steps = steps,
            prepared = prepared(tracker, steps),
            afterPersist = { msgId -> tracker.accept(msgId, CustodyRoute.LAN) },
        )

        val result = coordinator.execute()

        assertEquals(LocalUnpairCustodyOutcome.LAN, result.custody)
        assertEquals(MSG_ID, result.msgId)
        assertEquals(
            listOf("graceful-call", "reserve", "persist:$MSG_ID", "custody:LAN", "quiesce", "revoke", "wipe"),
            steps,
        )
    }

    @Test
    fun relayCustodyReleasesTeardownInExactOrder() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val coordinator = coordinator(
            steps = steps,
            prepared = prepared(tracker, steps),
            afterPersist = { msgId -> tracker.accept(msgId, CustodyRoute.RELAY) },
        )

        val result = coordinator.execute()

        assertEquals(LocalUnpairCustodyOutcome.RELAY, result.custody)
        assertEquals(
            listOf("graceful-call", "reserve", "persist:$MSG_ID", "custody:RELAY", "quiesce", "revoke", "wipe"),
            steps,
        )
    }

    @Test
    fun innerTimeoutIsFailOpenAndBounded() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator(steps, prepared(tracker, steps), timeoutMillis = 5_000L).execute()
        }

        runCurrent()
        assertEquals(listOf("graceful-call", "reserve", "persist:$MSG_ID"), steps)
        advanceTimeBy(5_000L)
        runCurrent()

        assertEquals(LocalUnpairCustodyOutcome.TIMEOUT, result.await().custody)
        assertEquals(
            listOf("graceful-call", "reserve", "persist:$MSG_ID", "custody:TIMEOUT", "quiesce", "revoke", "wipe"),
            steps,
        )
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun transportUnavailablePersistsThenContinuesSecureTeardown() = runTest {
        val steps = mutableListOf<String>()
        val prepared = PreparedLocalUnpairService.unavailable { steps += "quiesce" }

        val result = coordinator(steps, prepared).execute()

        assertEquals(LocalUnpairCustodyOutcome.UNAVAILABLE, result.custody)
        assertEquals(
            listOf("graceful-call", "persist:$MSG_ID", "custody:UNAVAILABLE", "quiesce", "revoke", "wipe"),
            steps,
        )
    }

    @Test
    fun nonCancellationDeliveryFailureIsFailOpen() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()

        val result = coordinator(
            steps,
            prepared(tracker, steps),
            persist = { msgId ->
                steps += "persist:$msgId"
                throw IllegalStateException("database unavailable")
            },
        ).execute()

        assertEquals(LocalUnpairCustodyOutcome.DELIVERY_FAILED, result.custody)
        assertEquals(
            listOf("graceful-call", "reserve", "persist:$MSG_ID", "custody:DELIVERY_FAILED", "quiesce", "revoke", "wipe"),
            steps,
        )
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun custodyOutcomeObserverFailureCannotPreventSecureTeardown() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val prepared = prepared(tracker, steps)
        val result = LocalUnpairCoordinator(
            prepare = {
                steps += "graceful-call"
                prepared
            },
            persistUnpair = { msgId ->
                steps += "persist:$msgId"
                tracker.accept(msgId, CustodyRoute.LAN)
                msgId
            },
            revokePeer = { steps += "revoke" },
            wipeLocal = { steps += "wipe" },
            newMessageId = { MSG_ID },
            onCustodyOutcome = {
                steps += "custody:${it.name}"
                throw IllegalStateException("observer unavailable")
            },
        ).execute()

        assertEquals(LocalUnpairCustodyOutcome.LAN, result.custody)
        assertEquals(
            listOf(
                "graceful-call",
                "reserve",
                "persist:$MSG_ID",
                "custody:LAN",
                "quiesce",
                "revoke",
                "wipe",
            ),
            steps,
        )
    }

    @Test
    fun terminalizationFailureNeverPersistsOrTearsDown() = runTest {
        val steps = mutableListOf<String>()
        val expected = IllegalStateException("call terminalization failed")
        val coordinator = LocalUnpairCoordinator(
            prepare = {
                steps += "graceful-call"
                throw expected
            },
            persistUnpair = { error("must not persist") },
            revokePeer = { steps += "revoke" },
            wipeLocal = { steps += "wipe" },
            newMessageId = { MSG_ID },
        )

        val thrown = assertFailsWith<IllegalStateException> { coordinator.execute() }

        assertSame(expected, thrown)
        assertEquals(listOf("graceful-call"), steps)
    }

    @Test
    fun callTerminalRowsAndDisabledConfigPrecedeUnpairPersistence() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val prepared = PreparedLocalUnpairService.available(tracker, onReserve = { steps += "reserve" }) {
            steps += "quiesce"
        }
        val result = LocalUnpairCoordinator(
            prepare = {
                steps += "persist-call-idle"
                steps += "persist-service-disabled"
                prepared
            },
            persistUnpair = { msgId ->
                steps += "persist-unpair"
                tracker.accept(msgId, CustodyRoute.LAN)
                msgId
            },
            revokePeer = { steps += "revoke" },
            wipeLocal = { steps += "wipe" },
            newMessageId = { MSG_ID },
        ).execute()

        assertEquals(LocalUnpairCustodyOutcome.LAN, result.custody)
        assertEquals(
            listOf(
                "persist-call-idle",
                "persist-service-disabled",
                "reserve",
                "persist-unpair",
                "quiesce",
                "revoke",
                "wipe",
            ),
            steps,
        )
    }

    @Test
    fun outerCancellationEscapesByIdentityBeforeRevokeAndWipe() = runTest {
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val cancellation = CancellationException("caller cancelled")
        val prepared = prepared(tracker, steps)
        val coordinator = coordinator(
            steps,
            prepared,
            persist = { msgId ->
                steps += "persist:$msgId"
                throw cancellation
            },
        )

        val thrown = assertFailsWith<CancellationException> { coordinator.execute() }

        assertSame(cancellation, thrown)
        assertEquals(listOf("graceful-call", "reserve", "persist:$MSG_ID"), steps)
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun outerBoundaryCancellationCancelsSoleInFlightRequestBeforeLaterTeardown() = runTest {
        val gate = LocalUnpairRequestGate()
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val coordinator = coordinator(
            steps = steps,
            prepared = prepared(tracker, steps),
            timeoutMillis = 5_000L,
        )
        var boundaryCancellation: CancellationException? = null
        val boundary = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitLocalUnpairResult(gate.start(backgroundScope, coordinator::execute))
            } catch (cancellation: CancellationException) {
                boundaryCancellation = cancellation
                throw cancellation
            }
        }
        runCurrent()
        assertEquals(listOf("graceful-call", "reserve", "persist:$MSG_ID"), steps)

        val cancellation = CancellationException("cancel Expo unpair boundary")
        boundary.cancel(cancellation)
        runCurrent()

        assertFailsWith<CancellationException> { boundary.await() }
        assertSame(cancellation, boundaryCancellation)
        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(listOf("graceful-call", "reserve", "persist:$MSG_ID"), steps)
        assertEquals(0, tracker.pendingCount())
    }

    @Test
    fun outerBoundaryCancellationOfOneConcurrentWaiterKeepsOtherAuthorizedWaiterAlive() = runTest {
        val gate = LocalUnpairRequestGate()
        val steps = mutableListOf<String>()
        val tracker = UnpairCustodyTracker()
        val coordinator = coordinator(
            steps = steps,
            prepared = prepared(tracker, steps),
            timeoutMillis = 5_000L,
        )
        var firstBoundaryCancellation: CancellationException? = null
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitLocalUnpairResult(gate.start(backgroundScope, coordinator::execute))
            } catch (cancellation: CancellationException) {
                firstBoundaryCancellation = cancellation
                throw cancellation
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            awaitLocalUnpairResult(gate.start(backgroundScope, coordinator::execute))
        }
        runCurrent()

        val cancellation = CancellationException("cancel first Expo waiter")
        first.cancel(cancellation)
        runCurrent()
        assertFailsWith<CancellationException> { first.await() }
        assertSame(cancellation, firstBoundaryCancellation)
        assertFalse(second.isCompleted)
        assertEquals(listOf("graceful-call", "reserve", "persist:$MSG_ID"), steps)

        tracker.accept(MSG_ID, CustodyRoute.LAN)
        assertEquals(LocalUnpairCustodyOutcome.LAN, second.await().custody)
        assertEquals(
            listOf(
                "graceful-call",
                "reserve",
                "persist:$MSG_ID",
                "custody:LAN",
                "quiesce",
                "revoke",
                "wipe",
            ),
            steps,
        )
    }

    @Test
    fun requestsConcurrentShareOneMessageAndOneResult() = runTest {
        val gate = LocalUnpairRequestGate()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        var persisted = 0
        val request: suspend () -> LocalUnpairResult = {
            requests += 1
            persisted += 1
            release.await()
            LocalUnpairResult(MSG_ID, LocalUnpairCustodyOutcome.LAN)
        }

        val first = gate.start(backgroundScope, request)
        val second = gate.start(backgroundScope, request)
        runCurrent()
        release.complete(Unit)

        assertFalse(first === second)
        assertEquals(true, first.sharesExecutionWith(second))
        assertEquals(MSG_ID, awaitLocalUnpairResult(first).msgId)
        assertEquals(MSG_ID, awaitLocalUnpairResult(second).msgId)
        assertEquals(1, requests)
        assertEquals(1, persisted)
    }

    @Test
    fun laterRequestAfterCompletionCanReturnNoPeerWithoutAnotherMessage() = runTest {
        val gate = LocalUnpairRequestGate()
        var persisted = 0
        val first = gate.start(backgroundScope) {
            persisted += 1
            LocalUnpairResult(MSG_ID, LocalUnpairCustodyOutcome.LAN)
        }
        assertEquals(MSG_ID, awaitLocalUnpairResult(first).msgId)
        runCurrent()

        val later = gate.start(backgroundScope) {
            LocalUnpairResult(null, LocalUnpairCustodyOutcome.NO_PEER)
        }

        assertEquals(LocalUnpairCustodyOutcome.NO_PEER, awaitLocalUnpairResult(later).custody)
        assertEquals(1, persisted)
    }

    @Test
    fun productionStatusRecordsEveryContentFreeOutcomeWithoutMessageId() = runTest {
        val expectedCodes = mapOf(
            LocalUnpairCustodyOutcome.LAN to "lan",
            LocalUnpairCustodyOutcome.RELAY to "relay",
            LocalUnpairCustodyOutcome.TIMEOUT to "timeout",
            LocalUnpairCustodyOutcome.UNAVAILABLE to "unavailable",
            LocalUnpairCustodyOutcome.DELIVERY_FAILED to "delivery_failed",
            LocalUnpairCustodyOutcome.NO_PEER to "no_peer",
        )

        expectedCodes.forEach { (outcome, code) ->
            val gate = LocalUnpairRequestGate()
            val result = awaitLocalUnpairResultAndRecord(
                gate.start(backgroundScope) { LocalUnpairResult(MSG_ID, outcome) },
            )

            assertEquals(outcome, result.custody)
            assertEquals(code, LocalUnpairStatus.lastOutcome.value)
            assertFalse(LocalUnpairStatus.lastOutcome.value.orEmpty().contains(MSG_ID))
        }
    }

    private fun coordinator(
        steps: MutableList<String>,
        prepared: PreparedLocalUnpairService,
        persist: suspend (String) -> String = { msgId ->
            steps += "persist:$msgId"
            msgId
        },
        afterPersist: (String) -> Unit = {},
        timeoutMillis: Long = 5_000L,
    ) = LocalUnpairCoordinator(
        prepare = {
            steps += "graceful-call"
            prepared
        },
        persistUnpair = { msgId ->
            persist(msgId).also(afterPersist)
        },
        revokePeer = { steps += "revoke" },
        wipeLocal = { steps += "wipe" },
        newMessageId = { MSG_ID },
        custodyTimeoutMillis = timeoutMillis,
        onCustodyOutcome = { steps += "custody:${it.name}" },
    )

    private fun prepared(
        tracker: UnpairCustodyTracker,
        steps: MutableList<String>,
    ) = PreparedLocalUnpairService.available(
        tracker = tracker,
        onReserve = { steps += "reserve" },
        quiesce = { steps += "quiesce" },
    )

    companion object {
        private const val MSG_ID = "00000000-0000-0000-0000-000000000002"
    }
}
