package co.twinotify.core.listener

import co.twinotify.core.storage.CanonicalNotificationState
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureReconciliationTest {
    @Test
    fun reconnectCancelsPersistedSourcesRemovedWhileListenerWasDown() {
        val states = listOf(
            state("canon-live", "source-live", "ACTIVE"),
            state("canon-removed", "source-removed", "ACTIVE"),
            state("canon-cancelled", "source-cancelled", "CANCELLED"),
            state("canon-no-key", null, "ACTIVE"),
        )

        val missing = CaptureReconciliation.missingActiveStates(states, setOf("source-live"))

        assertEquals(listOf("canon-removed"), missing.map { it.canonId })
    }

    @Test
    fun recoveryEmitsEachFinalActivePostAndMissingActiveRemovalOnce() {
        val snapshots = listOf(snapshot("source-live", "pkg.live", 1), snapshot("source-new", "pkg.new", 2))
        val states = listOf(
            state("canon-live", "source-live", "ACTIVE"),
            state("canon-removed", "source-removed", "ACTIVE"),
        )

        val commands = CaptureReconciliation.recoveryCommands(
            originDevice = "device-a",
            snapshots = snapshots,
            states = states,
            removedAt = 100,
        ).toList()

        assertEquals(listOf("pkg.live", "pkg.new"), commands.filterIsInstance<PostCommand>().map { it.snapshot.packageName })
        assertEquals(listOf("canon-removed"), commands.filterIsInstance<RemoveCommand>().map { it.canonId })
    }

    @Test
    fun peerMirrorRemovalRequiresPeerActiveRowsComparedWithFullMirrorIdentities() {
        val peerMirror = state("peer-canon", "peer-source", "ACTIVE").copy(
            originDevice = "peer-device",
            mirrorLocalTag = "mirror-tag",
            mirrorLocalId = 7,
        )

        assertEquals(
            emptyList(),
            CaptureReconciliation.missingPeerMirrorStates(
                states = listOf(peerMirror),
                liveMirrorIdentities = setOf(MirrorNotificationIdentity("mirror-tag", 7)),
            ),
        )
        assertEquals(
            listOf("peer-canon"),
            CaptureReconciliation.missingPeerMirrorStates(
                states = listOf(peerMirror),
                liveMirrorIdentities = emptySet(),
            ).map { it.canonId },
        )
    }

    @Test
    fun recoveryDoesNotTurnAMissingPeerCallMirrorIntoANotificationCancel() {
        val peerCall = state("call:peer-call", "peer-source", "ACTIVE").copy(
            originDevice = "peer-device",
            mirrorLocalTag = "call-mirror",
            mirrorLocalId = 8,
        )

        val commands = CaptureReconciliation.recoveryCommands(
            originDevice = "device-a",
            snapshots = emptyList(),
            states = emptyList(),
            peerMirrorStates = listOf(peerCall),
            liveMirrorIdentities = emptySet(),
            removedAt = 100,
        ).toList()

        assertEquals(emptyList(), commands.filterIsInstance<RemoveCommand>())
        assertEquals(emptyList(), CaptureReconciliation.missingPeerMirrorStates(listOf(peerCall), emptySet()))
    }

    private fun state(canonId: String, sourceKey: String?, state: String) =
        CanonicalNotificationState(
            canonId = canonId,
            originDevice = "device-a",
            latestSequence = 1,
            state = state,
            desiredPayloadJson = null,
            materializedSequence = 0,
            sourceNotificationKey = sourceKey,
            mirrorLocalId = null,
            mirrorLocalTag = null,
            peerCancelPending = false,
            updatedAt = 1,
        )

    private fun snapshot(sourceKey: String, packageName: String, id: Int) = SourceNotificationSnapshot(
        sourceKey = sourceKey,
        packageName = packageName,
        id = id,
        tag = null,
        postTime = 1,
        flags = 0,
        category = null,
        visibility = 0,
        isGroupSummary = false,
        isOngoing = false,
        isClearable = true,
        appName = null,
        title = null,
        text = null,
        subText = null,
        bigText = null,
        smallIcon = null,
        largeIcon = null,
    )
}
