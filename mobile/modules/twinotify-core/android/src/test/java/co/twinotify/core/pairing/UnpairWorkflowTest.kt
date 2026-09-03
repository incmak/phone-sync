package co.twinotify.core.pairing

import co.twinotify.core.call.CALL_SHUTDOWN_FAILED
import co.twinotify.core.call.CallShutdownConfigIntent
import co.twinotify.core.call.GracefulCallShutdownGate
import co.twinotify.core.call.GracefulCallShutdownResult
import co.twinotify.core.service.CallShutdownPhaseState
import co.twinotify.core.service.awaitCallShutdownResult
import co.twinotify.core.service.executeCallCaptureStopRequest
import co.twinotify.core.service.executeCallShutdownPhases
import co.twinotify.core.service.executePeerUnpairAndRequestServiceStop
import co.twinotify.core.service.quiesceServiceJobsAfterCallShutdown
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Exercises the exact workflow shared by local and peer-initiated production paths. */
class UnpairWorkflowTest {
    @Test
    fun productionWorkflowStopsBeforeRevokeAndWipe() = runBlocking {
        val steps = mutableListOf<String>()

        UnpairWorkflow.execute(
            stopAndAwait = { steps += "stop-and-await" },
            revokePeer = { steps += "revoke" },
            wipeLocal = { steps += "wipe" },
        )

        assertEquals(listOf("stop-and-await", "revoke", "wipe"), steps)
    }

    @Test
    fun gracefulUnpairTerminalizesEveryActiveCallBeforeJobsRevokeAndWipe() = runTest {
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        val activeCalls = linkedSetOf("call-a", "call-b")
        val steps = mutableListOf<String>()
        val shutdown = gate.start(
            backgroundScope,
            CallShutdownConfigIntent(disableCallCapture = false, disableService = true),
        ) {
            executeCallShutdownPhases(
                gate = gate,
                phaseState = phaseState,
                terminalize = {
                    steps += "unregister-source"
                    steps += "finish-in-flight-callback"
                    activeCalls.toList().forEach { call ->
                        steps += "persist-terminal-$call"
                        activeCalls.remove(call)
                    }
                },
                persistIntent = {
                    assertTrue(activeCalls.isEmpty())
                    steps += "persist-service-disabled"
                },
                reportFailure = { error("unexpected failure: $it") },
            )
        }

        UnpairWorkflow.execute(
            stopAndAwait = {
                executeCallCaptureStopRequest(
                    sharedShutdown = { shutdown.await() },
                    finalizeStop = {
                        quiesceServiceJobsAfterCallShutdown(
                            fromRelayJob = false,
                            activeRelay = null,
                            stopOtherChildren = { steps += "stop-service-jobs" },
                            cancelAndJoinServiceScope = {},
                        )
                    },
                )
            },
            revokePeer = {
                assertTrue(activeCalls.isEmpty())
                steps += "revoke-peer"
            },
            wipeLocal = {
                assertTrue(activeCalls.isEmpty())
                steps += "wipe-local"
            },
        )

        assertEquals(
            listOf(
                "unregister-source",
                "finish-in-flight-callback",
                "persist-terminal-call-a",
                "persist-terminal-call-b",
                "persist-service-disabled",
                "stop-service-jobs",
                "revoke-peer",
                "wipe-local",
            ),
            steps,
        )
        assertFalse(gate.isReserved())
    }

    @Test
    fun terminalExhaustionLeavesActiveJournalAndSkipsEveryLaterUnpairStep() = runTest {
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        var activeJournal = true
        var terminalAttempts = 0
        var configAttempts = 0
        var serviceStops = 0
        var revokes = 0
        var wipes = 0

        val failure = assertFailsWith<co.twinotify.core.call.ActiveCallRecoveryException> {
            UnpairWorkflow.execute(
                stopAndAwait = {
                    executeCallCaptureStopRequest(
                        sharedShutdown = {
                            gate.start(
                                backgroundScope,
                                CallShutdownConfigIntent(false, true),
                            ) {
                                executeCallShutdownPhases(
                                    gate = gate,
                                    phaseState = phaseState,
                                    terminalize = {
                                        terminalAttempts += 1
                                        throw IllegalStateException("terminal store unavailable")
                                    },
                                    persistIntent = { configAttempts += 1 },
                                    reportFailure = {},
                                )
                            }.await()
                        },
                        finalizeStop = { serviceStops += 1 },
                    )
                },
                revokePeer = { revokes += 1 },
                wipeLocal = {
                    wipes += 1
                    activeJournal = false
                },
            )
        }

        assertEquals(CALL_SHUTDOWN_FAILED, failure.code)
        assertEquals(3, terminalAttempts)
        assertEquals(0, configAttempts)
        assertEquals(0, serviceStops)
        assertEquals(0, revokes)
        assertEquals(0, wipes)
        assertTrue(activeJournal)
        assertTrue(gate.isReserved())
    }

    @Test
    fun configExhaustionAfterTerminalCustodySkipsJobsRevokeAndWipeAndKeepsAdmission() = runTest {
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        var activeJournal = true
        var terminalAttempts = 0
        var configAttempts = 0
        var serviceStops = 0
        var revokes = 0
        var wipes = 0

        assertFailsWith<co.twinotify.core.call.ActiveCallRecoveryException> {
            UnpairWorkflow.execute(
                stopAndAwait = {
                    executeCallCaptureStopRequest(
                        sharedShutdown = {
                            gate.start(
                                backgroundScope,
                                CallShutdownConfigIntent(false, true),
                            ) {
                                executeCallShutdownPhases(
                                    gate = gate,
                                    phaseState = phaseState,
                                    terminalize = {
                                        terminalAttempts += 1
                                        activeJournal = false
                                    },
                                    persistIntent = {
                                        configAttempts += 1
                                        throw IllegalStateException("config unavailable")
                                    },
                                    reportFailure = {},
                                )
                            }.let { awaitCallShutdownResult(it) }
                        },
                        finalizeStop = { serviceStops += 1 },
                    )
                },
                revokePeer = { revokes += 1 },
                wipeLocal = { wipes += 1 },
            )
        }

        assertEquals(1, terminalAttempts)
        assertEquals(3, configAttempts)
        assertEquals(0, serviceStops)
        assertEquals(0, revokes)
        assertEquals(0, wipes)
        assertFalse(activeJournal)
        assertTrue(phaseState.hasTerminalCustody())
        assertTrue(gate.isReserved())
    }

    @Test
    fun gracefulShutdownCancellationEscapesUnpairByIdentity() = runTest {
        val cancellation = CancellationException("cancel graceful unpair")
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        var revokes = 0
        var wipes = 0

        val thrown = assertFailsWith<CancellationException> {
            UnpairWorkflow.execute(
                stopAndAwait = {
                    executeCallCaptureStopRequest(
                        sharedShutdown = {
                            gate.start(
                                backgroundScope,
                                CallShutdownConfigIntent(false, true),
                            ) {
                                executeCallShutdownPhases(
                                    gate = gate,
                                    phaseState = phaseState,
                                    terminalize = { throw cancellation },
                                    persistIntent = { error("config must not run") },
                                    reportFailure = { error("cancellation must not be reported") },
                                )
                            }.let { awaitCallShutdownResult(it) }
                        },
                        finalizeStop = { error("service jobs must not stop") },
                    )
                },
                revokePeer = { revokes += 1 },
                wipeLocal = { wipes += 1 },
            )
        }

        assertSame(cancellation, thrown)
        assertEquals(0, revokes)
        assertEquals(0, wipes)
    }

    @Test
    fun nestedSameMessageCancellationPreservesOuterIdentityThroughUnpair() = runTest {
        val inner = CancellationException("cancel graceful unpair")
        val outer = CancellationException("cancel graceful unpair").apply { initCause(inner) }
        val gate = GracefulCallShutdownGate()
        val phaseState = CallShutdownPhaseState()
        var revokes = 0
        var wipes = 0

        val thrown = assertFailsWith<CancellationException> {
            UnpairWorkflow.execute(
                stopAndAwait = {
                    executeCallCaptureStopRequest(
                        sharedShutdown = {
                            gate.start(
                                backgroundScope,
                                CallShutdownConfigIntent(false, true),
                            ) {
                                executeCallShutdownPhases(
                                    gate = gate,
                                    phaseState = phaseState,
                                    terminalize = { throw outer },
                                    persistIntent = { error("config must not run") },
                                    reportFailure = { error("cancellation must not be reported") },
                                )
                            }.let { awaitCallShutdownResult(it) }
                        },
                        finalizeStop = { error("service jobs must not stop") },
                    )
                },
                revokePeer = { revokes += 1 },
                wipeLocal = { wipes += 1 },
            )
        }

        assertSame(outer, thrown)
        assertEquals(0, revokes)
        assertEquals(0, wipes)
    }

    @Test
    fun peerRelayJobWipesWithoutOutboundResponseOrCustodyWaitBeforeSafeServiceStop() = runTest {
        val currentRelay = currentCoroutineContext()[Job]!!
        val steps = mutableListOf<String>()
        var parentScopeStops = 0
        var onDestroyCancellations = 0

        executePeerUnpairAndRequestServiceStop(
            unpair = {
                UnpairWorkflow.execute(
                    stopAndAwait = {
                        steps += "graceful-call-shutdown"
                        quiesceServiceJobsAfterCallShutdown(
                            fromRelayJob = true,
                            activeRelay = currentRelay,
                            stopOtherChildren = {
                                currentCoroutineContext().ensureActive()
                                steps += "stop-other-service-children"
                            },
                            cancelAndJoinServiceScope = { parentScopeStops += 1 },
                        )
                    },
                    revokePeer = {},
                    wipeLocal = {
                        currentCoroutineContext().ensureActive()
                        steps += "wipe-local"
                    },
                )
            },
            requestServiceStop = {
                currentCoroutineContext().ensureActive()
                steps += "request-service-stop"
                steps += "on-destroy-best-effort-close"
                onDestroyCancellations += 1
            },
        )

        assertTrue(currentRelay.isActive)
        assertEquals(0, parentScopeStops)
        assertEquals(1, onDestroyCancellations)
        assertEquals(
            listOf(
                "graceful-call-shutdown",
                "stop-other-service-children",
                "wipe-local",
                "request-service-stop",
                "on-destroy-best-effort-close",
            ),
            steps,
        )
    }

    @Test
    fun localUnpairCancelsAndJoinsRelayAndParentScopeAfterGracefulShutdown() = runTest {
        val relay = CompletableDeferred<Unit>()
        val serviceScope = CompletableDeferred<Unit>()
        val steps = mutableListOf<String>()

        quiesceServiceJobsAfterCallShutdown(
            fromRelayJob = false,
            activeRelay = relay,
            stopOtherChildren = {
                assertTrue(relay.isCancelled)
                steps += "stop-other-service-children"
            },
            cancelAndJoinServiceScope = {
                serviceScope.cancelAndJoin()
                steps += "cancel-service-scope"
            },
        )

        assertTrue(relay.isCancelled)
        assertTrue(serviceScope.isCancelled)
        assertEquals(listOf("stop-other-service-children", "cancel-service-scope"), steps)
    }

    @Test
    fun fullWipeDeletesLanIdentityBeforeRotatingApplicationKeys() = runBlocking {
        val steps = mutableListOf<String>()

        UnpairWipeOrder(
            deleteLanIdentity = { steps += "lan-identity-delete" },
        ).beforeApplicationKeyRotation {
            steps += "application-key-rotation"
        }

        assertEquals(listOf("lan-identity-delete", "application-key-rotation"), steps)
    }

    @Test
    fun fullWipeClearsEveryDirectBindingBeforeRotatingApplicationKeys() = runBlocking {
        val steps = mutableListOf<String>()

        UnpairWipeOrder(
            clearLanBinding = { steps += "lan-binding-clear" },
            deleteLanIdentity = { steps += "lan-identity-delete" },
            clearBluetoothBinding = { steps += "bluetooth-binding-clear" },
        ).beforeApplicationKeyRotation {
            steps += "application-key-rotation"
        }

        assertEquals(
            listOf("lan-binding-clear", "lan-identity-delete", "bluetooth-binding-clear", "application-key-rotation"),
            steps,
        )
    }

    @Test
    fun bluetoothUnpairClearsBindingAndDisassociatesOnlyTheStoredId() = runBlocking {
        val disassociated = mutableListOf<Int>()
        val reported = mutableListOf<String>()
        var clears = 0

        BluetoothUnpairOps.clearBindingAndDisassociate(
            storedAssociationId = { 41 },
            clearBinding = { clears += 1 },
            disassociate = { id -> disassociated += id; true },
            report = { reported += it },
        )
        BluetoothUnpairOps.clearBindingAndDisassociate(
            storedAssociationId = { null },
            clearBinding = { clears += 1 },
            disassociate = { id -> disassociated += id; true },
            report = { reported += it },
        )

        assertEquals(2, clears)
        assertEquals(listOf(41), disassociated)
        assertTrue(reported.isEmpty())
    }

    @Test
    fun bluetoothDisassociationFailureIsReportedAsBoundedCodeWithoutBlockingTheWipe() = runBlocking {
        val reported = mutableListOf<String>()
        var clears = 0

        BluetoothUnpairOps.clearBindingAndDisassociate(
            storedAssociationId = { 41 },
            clearBinding = { clears += 1 },
            disassociate = { false },
            report = { reported += it },
        )
        BluetoothUnpairOps.clearBindingAndDisassociate(
            storedAssociationId = { 42 },
            clearBinding = { clears += 1 },
            disassociate = { throw IllegalStateException("cdm unavailable") },
            report = { reported += it },
        )
        BluetoothUnpairOps.clearBindingAndDisassociate(
            storedAssociationId = { throw IllegalStateException("store unreadable") },
            clearBinding = { clears += 1 },
            disassociate = { error("no id to remove") },
            report = { reported += it },
        )

        assertEquals(3, clears, "a disassociation failure must not keep the local binding")
        assertEquals(
            listOf(BLUETOOTH_DISASSOCIATION_REQUIRED, BLUETOOTH_DISASSOCIATION_REQUIRED, BLUETOOTH_DISASSOCIATION_REQUIRED),
            reported,
        )
        assertEquals("bluetooth_disassociation_required", BLUETOOTH_DISASSOCIATION_REQUIRED)
    }
}
