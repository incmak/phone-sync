package co.twinotify.core.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import co.twinotify.core.listener.NotifPostJson
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import co.twinotify.core.storage.NotificationDb
import org.junit.After
import org.junit.Before
import org.junit.Test

class RepeatProtectionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val base = System.currentTimeMillis()
    private val canonId = "repeat-test-peer:example.vpn:42:speed"
    private val tag = NotificationStateReducer.stableMirrorTag(canonId)
    private val mirrorId = MirrorPoster.stableLocalId(canonId)

    @Before fun setUp() {
        instrumentation.uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.POST_NOTIFICATIONS)
        RepeatProtection.clear(context)
        manager.activeNotifications.filter { it.tag == tag || it.tag == "other-repeat-test" }
            .forEach { manager.cancel(it.tag, it.id) }
        runBlocking { NotificationDb.get(context).notificationMapDao().deleteByCanonId(canonId) }
    }

    @After fun tearDown() {
        RepeatProtection.clear(context)
        manager.cancel(tag, mirrorId)
        manager.cancel("other-repeat-test", 43)
        instrumentation.uiAutomation.dropShellPermissionIdentity()
    }

    private fun post(at: Long, canon: String = canonId, localTag: String = tag, id: Int = mirrorId) = NotifPostJson(
        type = "notif.update", canon_id = canon, app_name = "VPN", package_name = "example.vpn", id = id,
        tag = "speed", title = "VPN connected", text = "Speed $at", sub_text = null, big_text = null,
        visibility = "private", is_group_summary = false, is_ongoing = true, is_clearable = false,
        small_icon_png_b64 = null, large_icon_png_b64 = null, ts = base + at,
    ).also { post ->
        RepeatProtection.present(context, post, "legacy:${post.ts}", localTag, id, legacy = true, nowMs = base + at) {
            manager.notify(localTag, id, MirrorPoster.buildNotification(context, post, id, localTag))
        }
    }

    @Test fun snoozeHidesOnlyTheRepeatingIdentityAndInstallsCancelTombstoneFirst() {
        assertTrue(RepeatProtection.enabled(context))
        post(0)
        post(5_000)
        post(6_000, "repeat-test-peer:example.vpn:43:other", "other-repeat-test", 43)
        post(10_000)
        assertTrue(RepeatProtection.isHidden(context, canonId))
        assertFalse(RepeatProtection.consumeRemoval(context, canonId, 2))
        assertTrue(RepeatProtection.consumeRemoval(context, canonId, 8))
        assertTrue(manager.activeNotifications.none { it.tag == tag })
        assertTrue(manager.activeNotifications.any { it.tag == "other-repeat-test" })
    }

    @Test fun continuingUpdatesBlockWithOneNoticeAndUndoRestoresOnlyThisNotification() {
        for (at in 0L..65_000L step 5_000L) post(at)
        RepeatProtection.recover(context, base + 70_000)
        assertEquals(1, RepeatProtection.blockedCount(context))
        val notices = manager.activeNotifications.filter { it.notification.channelId == "repeat_protection" }
        assertEquals(1, notices.size)
        assertEquals("Undo", notices.single().notification.actions.single().title.toString())
        RepeatProtection.recover(context, base + 71_000)
        assertEquals(1, manager.activeNotifications.count { it.notification.channelId == "repeat_protection" })
        notices.single().notification.actions.single().actionIntent.send()
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (RepeatProtection.isHidden(context, canonId) && android.os.SystemClock.elapsedRealtime() < deadline) {
            android.os.SystemClock.sleep(20)
        }
        assertEquals(0, RepeatProtection.blockedCount(context))
        assertFalse(RepeatProtection.isHidden(context, canonId))
        assertTrue(manager.activeNotifications.any { it.tag == tag })
        for (at in 80_000L..150_000L step 5_000L) post(at)
        assertFalse(RepeatProtection.isHidden(context, canonId))
    }

    @Test fun quietSnoozeRestoresLatestButSourceCancellationDoesNotResurrectIt() {
        post(0)
        post(5_000)
        post(10_000)
        RepeatProtection.recover(context, base + 70_000)
        assertFalse(RepeatProtection.isHidden(context, canonId))
        assertTrue(manager.activeNotifications.any { it.tag == tag })
        post(80_000)
        post(85_000)
        post(90_000)
        assertTrue(RepeatProtection.isHidden(context, canonId))
        RepeatProtection.cancelled(context, canonId)
        RepeatProtection.recover(context, base + 150_000)
        assertTrue(manager.activeNotifications.none { it.tag == tag })
    }

    @Test fun durableMirrorsDeduplicateRetriesAndRestoreTheLatestRoomPayload() = runBlocking {
        val dao = NotificationDb.get(context).reliableDeliveryDao()
        val v2Id = "repeat-v2-peer:example.vpn:99:speed"
        val v2Tag = NotificationStateReducer.stableMirrorTag(v2Id)
        val port = DefaultAndroidNotificationPort(context, "local", dao)
        try {
            for (sequence in 1L..3L) {
                val payload = """{"v":1,"type":"notif.update","canon_id":"$v2Id","app_name":"VPN","package_name":"example.vpn","id":99,"tag":"speed","title":"VPN","text":"Speed $sequence"}"""
                val canonical = co.twinotify.core.storage.CanonicalNotificationState(
                    canonId = v2Id, originDevice = "repeat-v2-peer", latestSequence = sequence,
                    state = "ACTIVE", desiredPayloadJson = payload, materializedSequence = sequence - 1,
                    sourceNotificationKey = null, mirrorLocalId = 99, mirrorLocalTag = v2Tag,
                    peerCancelPending = false, updatedAt = System.currentTimeMillis(),
                )
                val inbound = co.twinotify.core.storage.InboundMessage(
                    msgId = "repeat-v2-$sequence", originDevice = "repeat-v2-peer", envelopeSha256 = "$sequence".padStart(64, '0'),
                    eventType = "notif.update", canonId = v2Id, sequence = sequence, outcome = "PENDING_PLATFORM",
                    committedAt = System.currentTimeMillis(), appliedAt = null, receiptMsgId = null, relayAckState = "NONE",
                )
                assertEquals(
                    co.twinotify.core.storage.InboundDesiredCommitResult.Committed,
                    dao.commitInboundDesired(inbound, canonical),
                )
                assertEquals(NotificationPostOutcome.Applied, port.postMirrorOutcome(canonical))
                assertEquals(NotificationPostOutcome.Applied, port.postMirrorOutcome(canonical))
                assertEquals(sequence == 3L, RepeatProtection.isHidden(context, v2Id))
                assertEquals(
                    co.twinotify.core.storage.MaterializationResult.Completed,
                    dao.completeMaterialization(v2Id, sequence, System.currentTimeMillis(), null),
                )
            }
            assertFalse(dao.canonical(v2Id)!!.peerCancelPending)
            assertFalse(RepeatProtection.consumeRemoval(context, v2Id, 2))
            assertTrue(RepeatProtection.consumeRemoval(context, v2Id, 8))
            assertEquals("ACTIVE", dao.canonical(v2Id)!!.state)
            RepeatProtection.recover(context, System.currentTimeMillis() + 61_000)
            assertFalse(RepeatProtection.isHidden(context, v2Id))
            val restored = manager.activeNotifications.single { it.tag == v2Tag }
            assertEquals("Speed 3", restored.notification.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
        } finally {
            manager.cancel(v2Tag, 99)
            dao.clearReliableState()
        }
    }

    @Test fun disablingRestoresSnoozesAndKeepsFutureUpdatesVisible() {
        post(0)
        post(5_000)
        post(10_000)
        RepeatProtection.setEnabled(context, false)
        assertFalse(RepeatProtection.enabled(context))
        assertFalse(RepeatProtection.isHidden(context, canonId))
        for (at in 15_000L..90_000L step 5_000L) post(at)
        assertFalse(RepeatProtection.isHidden(context, canonId))
        assertEquals(0, RepeatProtection.blockedCount(context))
    }
}
