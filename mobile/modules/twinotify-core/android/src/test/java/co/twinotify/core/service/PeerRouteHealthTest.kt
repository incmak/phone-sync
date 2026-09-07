package co.twinotify.core.service

import co.twinotify.core.storage.DeliveryQueueSnapshot
import co.twinotify.core.storage.UserContentKind
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

class PeerRouteHealthTest {
    @After fun reset() = SyncServiceStatus.clearRouteStatus()

    @Test fun connectedPeerDoesNotOverwriteOtherPeersOutageOrQueue() {
        val generation = SyncServiceStatus.beginRouteGeneration()
        SyncServiceStatus.initializePeers(listOf("phone", "mac"), generation)
        SyncServiceStatus.setPeerRouteSnapshot("mac", SyncRouteStatus(route = RouteKind.RELAY, phase = RoutePhase.CONNECTING),
            queue(3), PeerEvidence.UNKNOWN, generation)
        SyncServiceStatus.setPeerRouteSnapshot("phone", SyncRouteStatus(route = RouteKind.LAN, phase = RoutePhase.AUTHENTICATED),
            queue(1), PeerEvidence.DIRECT, generation)
        assertEquals(RoutePhase.CONNECTING, SyncServiceStatus.peerRoutes.value.getValue("mac").status.phase)
        assertEquals(3, SyncServiceStatus.peerRoutes.value.getValue("mac").status.pendingLocalCount)
        assertEquals(1, SyncServiceStatus.peerRoutes.value.getValue("phone").status.pendingLocalCount)
        assertEquals(4, SyncServiceStatus.health.value.queuedCount)
        assertEquals("connecting", SyncServiceStatus.health.value.service)
    }

    @Test fun oldGenerationAndRemovedLinkCannotPublishIntoCurrentPeers() {
        val old = SyncServiceStatus.beginRouteGeneration()
        SyncServiceStatus.initializePeers(listOf("phone", "mac"), old)
        val current = SyncServiceStatus.beginRouteGeneration()
        SyncServiceStatus.initializePeers(listOf("phone", "mac"), current)
        SyncServiceStatus.setPeerConditions("phone", DeliveryConditions(bindingConflict = true), old)
        assertEquals(DeliveryReason.NONE, SyncServiceStatus.peerRoutes.value.getValue("phone").status.deliveryReason)
        SyncServiceStatus.removePeer("mac")
        SyncServiceStatus.setPeerRouteSnapshot("mac", SyncRouteStatus(route = RouteKind.RELAY, phase = RoutePhase.AUTHENTICATED),
            queue(7), PeerEvidence.RECENT, current)
        assertEquals(setOf("phone"), SyncServiceStatus.peerRoutes.value.keys)
    }

    private fun queue(count: Int) = DeliveryQueueSnapshot(count, 0, 0, 0, count, count.toLong(), UserContentKind.NOTIFICATIONS)
}
