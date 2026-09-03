package co.twinotify.core.e2e

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import co.twinotify.core.call.CallControlKind
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CallControlFixtureTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun fixtureNotificationsCarryExactTypedIntentsPerStateAndNoCallData() {
        val incoming = CallControlFixture.incoming(context)
        assertEquals(Notification.CATEGORY_CALL, incoming.category)
        assertNotNull(incoming.callIntent(Notification.EXTRA_ANSWER_INTENT))
        assertNotNull(incoming.callIntent(Notification.EXTRA_DECLINE_INTENT))
        assertNull(incoming.callIntent(Notification.EXTRA_HANG_UP_INTENT))
        assertTrue(incoming.callIntent(Notification.EXTRA_ANSWER_INTENT)!!.isImmutable)
        assertTrue(incoming.callIntent(Notification.EXTRA_ANSWER_INTENT) != incoming.callIntent(Notification.EXTRA_DECLINE_INTENT))

        val ongoing = CallControlFixture.ongoing(context)
        assertEquals(Notification.CATEGORY_CALL, ongoing.category)
        assertNull(ongoing.callIntent(Notification.EXTRA_ANSWER_INTENT))
        assertNull(ongoing.callIntent(Notification.EXTRA_DECLINE_INTENT))
        assertNotNull(ongoing.callIntent(Notification.EXTRA_HANG_UP_INTENT))

        val extras = incoming.extras.keySet().joinToString(" ") { "$it=${incoming.extras.get(it)}" }.lowercase()
        listOf("phone", "number", "contact", "caller", "audio").forEach { forbidden ->
            assertTrue(!extras.contains(forbidden) || forbidden == "caller" && extras.contains("call"), "forbidden field $forbidden")
        }
        assertEquals(null, incoming.fullScreenIntent)
    }

    @Test
    fun receiverJournalsOnlyTypedKindsAndResetsOnEachRingingCall() {
        CallControlFixture.resetForTest()
        val receiver = CallControlFixtureReceiver()
        receiver.onReceive(context, Intent().setData(Uri.parse(CallControlFixture.dataUri(CallControlKind.ANSWER))))
        receiver.onReceive(context, Intent().setData(Uri.parse(CallControlFixture.dataUri(CallControlKind.ANSWER))))
        receiver.onReceive(context, Intent().setData(Uri.parse("twinotify-e2e://call-control/answer/extra")))
        receiver.onReceive(context, Intent().setData(Uri.parse("https://example.invalid/answer")))
        receiver.onReceive(context, Intent())

        val dispatches = CallControlFixture.dispatches()
        assertEquals(setOf("answer"), dispatches.keys().asSequence().toSet())
        assertEquals(2, dispatches.getInt("answer"))

        val awaited = runBlocking { CallControlFixture.await("answer", 100) }
        assertEquals("ok", awaited.code)
        assertEquals("dispatched", awaited.payload!!.getString("status"))
        assertEquals(2, awaited.payload!!.getInt("count"))
        assertEquals(setOf("kind", "count", "status", "elapsed_ms"), awaited.payload!!.keys().asSequence().toSet())

        val timeout = runBlocking { CallControlFixture.await("hang_up", 50) }
        assertEquals("timeout", timeout.payload!!.getString("status"))
        assertEquals(0, timeout.payload!!.getInt("count"))
        assertEquals("invalid", runBlocking { CallControlFixture.await("answer", 0) }.code)
        assertEquals("invalid", runBlocking { CallControlFixture.await("replay", 10) }.code)

        CallControlFixture.resetForTest()
        assertEquals(0, CallControlFixture.dispatches().length())
    }

    @Test
    fun sourceAndTapAreClosedWorldWithoutALiveService() {
        assertEquals("invalid", runBlocking { CallControlFixture.source(context, "hold") }.code)
        assertEquals("unsupported", runBlocking { CallControlFixture.source(context, "ringing") }.code)
        CallControlControl.resetForTest()
        assertEquals("invalid", CallControlControl.tap(context, "mute").code)
        assertEquals("unsupported", CallControlControl.tap(context, "replay").code)
        assertEquals("unsupported", CallControlControl.tap(context, "answer").code)
        assertEquals(Notification.EXTRA_ANSWER_INTENT, CallControlControl.extraFor("answer"))
        assertEquals(Notification.EXTRA_DECLINE_INTENT, CallControlControl.extraFor("decline"))
        assertEquals(Notification.EXTRA_HANG_UP_INTENT, CallControlControl.extraFor("hang_up"))
    }

    @Test
    fun statusControlKindsAreBoundedAndSorted() {
        assertEquals(
            listOf("answer", "decline"),
            E2eStateProvider.callControlKinds(
                """{"call_session_id":"x","state":"ringing","direction":"incoming","controls":[{"control_id":"b","kind":"decline"},{"control_id":"a","kind":"answer"}]}""",
            ),
        )
        assertEquals(emptyList(), E2eStateProvider.callControlKinds("""{"controls":[]}"""))
        assertNull(E2eStateProvider.callControlKinds("""{"controls":[{"control_id":"a","kind":"mute"}]}"""))
        assertNull(E2eStateProvider.callControlKinds("""{"state":"ringing"}"""))
        assertNull(E2eStateProvider.callControlKinds(null))
        assertNull(E2eStateProvider.callControlKinds("not json"))
    }

    private fun Notification.callIntent(key: String): PendingIntent? =
        extras.getParcelable(key, PendingIntent::class.java)
}
