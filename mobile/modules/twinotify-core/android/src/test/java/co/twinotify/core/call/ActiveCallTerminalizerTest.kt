package co.twinotify.core.call

import co.twinotify.core.storage.CanonicalNotificationState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

class ActiveCallTerminalizerTest {
    @Test
    fun activeLocalCallProducesOneIdleAtTheNextSequence() = runTest {
        val canonId = "call:a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        val store = FakeStore(
            activeResult = listOf(state(canonId, ORIGIN, "ACTIVE", 2, "incoming")),
            sequences = mapOf(canonId to 3L),
        )
        val persister = RecordingPersister()

        val summary = ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(ActiveCallRecoverySummary(terminated = 1), summary)
        assertEquals(
            listOf(CallStateEvent("a1b2c3d4-e5f6-4789-abcd-0123456789ab", "idle", CallDirection.INCOMING, 3)),
            persister.events,
        )
        assertEquals(listOf("active:$ORIGIN", "next:$canonId"), store.operations)
    }

    @Test
    fun recoveryPassesExpectedLocalOriginAndTreatsOwnershipLossAsSuccess() = runTest {
        val canonId = "call:a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        val store = FakeStore(
            activeResult = listOf(state(canonId, ORIGIN, "ACTIVE", 2, "incoming")),
            sequences = mapOf(canonId to 3L),
        )
        val recoveryCalls = mutableListOf<Pair<CallStateEvent, String>>()
        val persister = CallStatePersister(
            sink = CallStateSink { error("ordinary capture sink must not run during recovery") },
            recoverySink = CallRecoveryStateSink { event, expectedLocalOrigin ->
                recoveryCalls += event to expectedLocalOrigin
                CallStatePersistResult.OwnershipLost
            },
        )

        val summary = ActiveCallTerminalizer(store, persister).recover(ORIGIN)

        assertEquals(ActiveCallRecoverySummary(terminated = 1), summary)
        assertEquals(
            listOf(
                CallStateEvent(
                    "a1b2c3d4-e5f6-4789-abcd-0123456789ab",
                    "idle",
                    CallDirection.INCOMING,
                    3,
                ) to ORIGIN,
            ),
            recoveryCalls,
        )
        assertEquals(listOf("active:$ORIGIN", "next:$canonId"), store.operations)
    }

    @Test
    fun activeLocalCallQueryExcludesNotificationsRemoteCallsAndCancelledCalls() = runTest {
        val localCall = state("call:a1b2c3d4-e5f6-4789-abcd-0123456789ab", ORIGIN, "ACTIVE", 2, "incoming")
        val rows = listOf(
            state("notif:email", ORIGIN, "ACTIVE", 7, null),
            localCall,
            state("call:22222222-2222-4222-8222-222222222222", PEER, "ACTIVE", 4, "outgoing"),
            state("call:33333333-3333-4333-8333-333333333333", ORIGIN, "CANCELLED", 5, "incoming"),
        )
        val store = FilteringStore(rows, mapOf(localCall.canonId to 3L))
        val persister = RecordingPersister()

        ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(listOf(localCall.canonId), store.returnedActiveCallIds)
        assertEquals(listOf(localCall.canonId), persister.events.map { "call:${it.callSessionId}" })
    }

    @Test
    fun multipleActiveCallsAreTerminatedInStoreOrder() = runTest {
        val first = state("call:a1b2c3d4-e5f6-4789-abcd-0123456789ab", ORIGIN, "ACTIVE", 2, "incoming")
        val second = state("call:b2c3d4e5-f6a7-4890-bcde-1234567890ab", ORIGIN, "ACTIVE", 8, "outgoing")
        val store = FakeStore(
            activeResult = listOf(first, second),
            sequences = mapOf(first.canonId to 3L, second.canonId to 9L),
        )
        val persister = RecordingPersister()

        ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(
            listOf(
                "a1b2c3d4-e5f6-4789-abcd-0123456789ab",
                "b2c3d4e5-f6a7-4890-bcde-1234567890ab",
            ),
            persister.events.map { it.callSessionId },
        )
        assertEquals(
            listOf("active:$ORIGIN", "next:${first.canonId}", "next:${second.canonId}"),
            store.operations,
        )
    }

    @Test
    fun malformedCanonicalIsRejectedWithoutPersisting() = runTest {
        val malformed = state("call:not-a-uuid", ORIGIN, "ACTIVE", 2, "incoming")
        val store = FakeStore(activeResult = listOf(malformed))
        val persister = RecordingPersister()

        val error = assertFailsWith<ActiveCallRecoveryException> {
            ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)
        }

        assertEquals("call_recovery_invalid_canonical", error.code)
        assertTrue(persister.events.isEmpty())
        assertEquals(listOf("active:$ORIGIN"), store.operations)
    }

    @Test
    fun uppercaseCanonicalIsRejectedWithoutPersisting() = runTest {
        val uppercase = state("call:AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", ORIGIN, "ACTIVE", 2, "incoming")
        val store = FakeStore(activeResult = listOf(uppercase))
        val persister = RecordingPersister()

        val error = assertFailsWith<ActiveCallRecoveryException> {
            ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)
        }

        assertEquals("call_recovery_invalid_canonical", error.code)
        assertTrue(persister.events.isEmpty())
    }

    @Test
    fun malformedDirectionFallsBackToUnknown() = runTest {
        val malformedPayload = "not-json"
        val canonId = "call:a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        val store = FakeStore(
            activeResult = listOf(state(canonId, ORIGIN, "ACTIVE", 2, malformedPayload)),
            sequences = mapOf(canonId to 3L),
        )
        val persister = RecordingPersister()

        ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(CallDirection.UNKNOWN, persister.events.single().direction)
    }

    @Test
    fun directionParsingAcceptsOnlyBoundedTopLevelStringValues() = runTest {
        val rows = listOf(
            state("call:11111111-1111-4111-8111-111111111111", ORIGIN, "ACTIVE", 1, "incoming"),
            state("call:22222222-2222-4222-8222-222222222222", ORIGIN, "ACTIVE", 2, "outgoing"),
            state("call:33333333-3333-4333-8333-333333333333", ORIGIN, "ACTIVE", 3, "unknown"),
            state("call:44444444-4444-4444-8444-444444444444", ORIGIN, "ACTIVE", 4, "{\"error\":{\"direction\":\"incoming\"}}"),
            state("call:55555555-5555-4555-8555-555555555555", ORIGIN, "ACTIVE", 5, "not json \"direction\":\"outgoing\""),
            state("call:66666666-6666-4666-8666-666666666666", ORIGIN, "ACTIVE", 6, "{\"direction\":42}"),
            state(
                "call:77777777-7777-4777-8777-777777777777",
                ORIGIN,
                "ACTIVE",
                7,
                "{\"direction\":\"incoming\",\"padding\":\"${"x".repeat(1_024)}\"}",
            ),
        )
        val store = FakeStore(
            activeResult = rows,
            sequences = rows.associate { it.canonId to it.latestSequence + 1 },
        )
        val persister = RecordingPersister()

        ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(
            listOf(
                CallDirection.INCOMING,
                CallDirection.OUTGOING,
                CallDirection.UNKNOWN,
                CallDirection.UNKNOWN,
                CallDirection.UNKNOWN,
                CallDirection.UNKNOWN,
                CallDirection.UNKNOWN,
            ),
            persister.events.map { it.direction },
        )
    }

    @Test
    fun cancellationFromStorePropagatesUnchanged() = runTest {
        val cancellation = CancellationException("store cancellation")
        val store = FakeStore(activeFailure = cancellation)
        val persister = RecordingPersister()

        val thrown = assertFailsWith<CancellationException> {
            ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)
        }

        assertSame(cancellation, thrown)
        assertTrue(persister.events.isEmpty())
    }

    @Test
    fun cancellationFromPersisterPropagatesUnchanged() = runTest {
        val cancellation = CancellationException("persister cancellation")
        val canonId = "call:a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        val store = FakeStore(
            activeResult = listOf(state(canonId, ORIGIN, "ACTIVE", 2, "incoming")),
            sequences = mapOf(canonId to 3L),
        )
        val persister = CallStatePersister { throw cancellation }

        val thrown = assertFailsWith<CancellationException> {
            ActiveCallTerminalizer(store, persister).recover(ORIGIN)
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun persistedAndDuplicateTerminalStatesAreBothSuccessful() = runTest {
        val first = state("call:a1b2c3d4-e5f6-4789-abcd-0123456789ab", ORIGIN, "ACTIVE", 2, "incoming")
        val second = state("call:b2c3d4e5-f6a7-4890-bcde-1234567890ab", ORIGIN, "ACTIVE", 4, "outgoing")
        val store = FakeStore(
            activeResult = listOf(first, second),
            sequences = mapOf(first.canonId to 3L, second.canonId to 5L),
        )
        val persister = RecordingPersister(
            ArrayDeque(
                listOf(
                    CallStatePersistResult.Persisted(3, "first"),
                    CallStatePersistResult.Duplicate(5, "second"),
                ),
            ),
        )

        val summary = ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(ActiveCallRecoverySummary(terminated = 2), summary)
        assertEquals(listOf(3L, 5L), persister.events.map { it.sequence })
    }

    @Test
    fun staleThenMissingCancelledOrNonlocalStateIsIdempotentSuccess() = runTest {
        val missing = state("call:a1b2c3d4-e5f6-4789-abcd-0123456789ab", ORIGIN, "ACTIVE", 2, "incoming")
        val cancelled = state("call:b2c3d4e5-f6a7-4890-bcde-1234567890ab", ORIGIN, "ACTIVE", 4, "outgoing")
        val nonlocal = state("call:c3d4e5f6-a7b8-4012-cdef-2345678901ab", ORIGIN, "ACTIVE", 6, "unknown")
        val store = FakeStore(
            activeResult = listOf(missing, cancelled, nonlocal),
            sequences = mapOf(missing.canonId to 3L, cancelled.canonId to 5L, nonlocal.canonId to 7L),
            canonicalResults = mapOf(
                missing.canonId to ArrayDeque(listOf(null)),
                cancelled.canonId to ArrayDeque(listOf(cancelled.copy(state = "CANCELLED"))),
                nonlocal.canonId to ArrayDeque(listOf(nonlocal.copy(originDevice = PEER))),
            ),
        )
        val persister = RecordingPersister(
            ArrayDeque(
                List(3) { CallStatePersistResult.Stale(latestSequence = 9) },
            ),
        )

        val summary = ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(ActiveCallRecoverySummary(terminated = 3), summary)
        assertEquals(3, persister.events.size)
        assertEquals(
            listOf("canonical:${missing.canonId}", "canonical:${cancelled.canonId}", "canonical:${nonlocal.canonId}"),
            store.operations.filter { it.startsWith("canonical:") },
        )
    }

    @Test
    fun staleActiveStateRecomputesSequenceAndRetries() = runTest {
        val active = state("call:a1b2c3d4-e5f6-4789-abcd-0123456789ab", ORIGIN, "ACTIVE", 2, "incoming")
        val store = FakeStore(
            activeResult = listOf(active),
            sequenceResults = mapOf(active.canonId to ArrayDeque(listOf(3L, 4L))),
            canonicalResults = mapOf(active.canonId to ArrayDeque(listOf(active))),
        )
        val persister = RecordingPersister(
            ArrayDeque(
                listOf(
                    CallStatePersistResult.Stale(latestSequence = 3),
                    CallStatePersistResult.Persisted(4, "retry"),
                ),
            ),
        )

        val summary = ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)

        assertEquals(ActiveCallRecoverySummary(terminated = 1), summary)
        assertEquals(listOf(3L, 4L), persister.events.map { it.sequence })
        assertEquals(
            listOf("next:${active.canonId}", "canonical:${active.canonId}", "next:${active.canonId}"),
            store.operations.drop(1),
        )
    }

    @Test
    fun staleActiveStateStopsAfterThreeAttemptsWithBoundedCode() = runTest {
        val active = state("call:a1b2c3d4-e5f6-4789-abcd-0123456789ab", ORIGIN, "ACTIVE", 2, "incoming")
        val store = FakeStore(
            activeResult = listOf(active),
            sequenceResults = mapOf(active.canonId to ArrayDeque(listOf(3L, 4L, 5L))),
            canonicalResults = mapOf(active.canonId to ArrayDeque(listOf(active, active, active))),
        )
        val persister = RecordingPersister(
            ArrayDeque(List(3) { CallStatePersistResult.Stale(latestSequence = 9) }),
        )

        val error = assertFailsWith<ActiveCallRecoveryException> {
            ActiveCallTerminalizer(store, persister.value).recover(ORIGIN)
        }

        assertEquals("call_recovery_stale", error.code)
        assertEquals("call_recovery_stale", error.message)
        assertEquals(3, persister.events.size)
        assertEquals(3, store.operations.count { it == "canonical:${active.canonId}" })
    }

    @Test
    fun ordinaryStoreAndPersisterFailuresHaveOnlyTheStaticRecoveryCode() = runTest {
        val storeFailure = IllegalStateException("secret database detail")
        val storeError = assertFailsWith<ActiveCallRecoveryException> {
            ActiveCallTerminalizer(FakeStore(activeFailure = storeFailure), RecordingPersister().value).recover(ORIGIN)
        }

        assertEquals("call_recovery_failed", storeError.code)
        assertEquals("call_recovery_failed", storeError.message)
        assertFalse(storeError.message.orEmpty().contains("secret database detail"))

        val canonId = "call:a1b2c3d4-e5f6-4789-abcd-0123456789ab"
        val persistError = assertFailsWith<ActiveCallRecoveryException> {
            ActiveCallTerminalizer(
                FakeStore(
                    activeResult = listOf(state(canonId, ORIGIN, "ACTIVE", 2, "incoming")),
                    sequences = mapOf(canonId to 3L),
                ),
                CallStatePersister { throw IllegalArgumentException("secret persister detail") },
            ).recover(ORIGIN)
        }

        assertEquals("call_recovery_failed", persistError.code)
        assertEquals("call_recovery_failed", persistError.message)
        assertFalse(persistError.message.orEmpty().contains("secret persister detail"))
    }

    private class RecordingPersister(
        private val results: ArrayDeque<CallStatePersistResult> = ArrayDeque(),
    ) {
        val events = mutableListOf<CallStateEvent>()
        val value = CallStatePersister { event ->
            events += event
            if (results.isEmpty()) {
                CallStatePersistResult.Persisted(event.sequence, "msg-${event.sequence}")
            } else {
                results.removeFirst()
            }
        }
    }

    private open class FakeStore(
        private val activeResult: List<CanonicalNotificationState> = emptyList(),
        private val sequences: Map<String, Long> = emptyMap(),
        private val sequenceResults: Map<String, ArrayDeque<Long>> = emptyMap(),
        private val canonicalResults: Map<String, ArrayDeque<CanonicalNotificationState?>> = emptyMap(),
        private val activeFailure: Throwable? = null,
    ) : ActiveCallRecoveryStore {
        val operations = mutableListOf<String>()

        override suspend fun activeLocalCalls(originDevice: String): List<CanonicalNotificationState> {
            operations += "active:$originDevice"
            activeFailure?.let { throw it }
            return activeResult
        }

        override suspend fun canonical(canonId: String): CanonicalNotificationState? {
            operations += "canonical:$canonId"
            if (canonicalResults.containsKey(canonId)) {
                return canonicalResults.getValue(canonId).removeFirst()
            }
            return activeResult.firstOrNull { it.canonId == canonId }
        }

        override suspend fun nextSequence(canonId: String): Long {
            operations += "next:$canonId"
            return sequenceResults[canonId]?.removeFirst()
                ?: sequences[canonId]
                ?: error("missing test sequence for $canonId")
        }
    }

    private class FilteringStore(
        private val rows: List<CanonicalNotificationState>,
        private val sequences: Map<String, Long>,
    ) : FakeStore(sequences = sequences) {
        var returnedActiveCallIds: List<String> = emptyList()

        override suspend fun activeLocalCalls(originDevice: String): List<CanonicalNotificationState> {
            val result = rows.filter {
                it.originDevice == originDevice &&
                    it.state == "ACTIVE" &&
                    it.canonId.startsWith("call:")
            }
            returnedActiveCallIds = result.map { it.canonId }
            operations += "active:$originDevice"
            return result
        }

        override suspend fun canonical(canonId: String): CanonicalNotificationState? =
            rows.firstOrNull { it.canonId == canonId }
    }

    private fun state(
        canonId: String,
        originDevice: String,
        state: String,
        latestSequence: Long,
        directionPayload: String?,
    ) = CanonicalNotificationState(
        canonId = canonId,
        originDevice = originDevice,
        latestSequence = latestSequence,
        state = state,
        desiredPayloadJson = directionPayload?.let {
            if (it == "incoming" || it == "outgoing") "{\"direction\":\"$it\"}" else it
        },
        materializedSequence = latestSequence,
        sourceNotificationKey = null,
        mirrorLocalId = null,
        mirrorLocalTag = null,
        peerCancelPending = false,
        updatedAt = latestSequence,
    )

    private companion object {
        const val ORIGIN = "device-local"
        const val PEER = "device-peer"
    }
}
