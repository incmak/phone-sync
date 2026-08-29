package co.twinotify.core.e2e

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.json.JSONObject

class NotificationActionFixtureTest {
    @Test
    fun commandIntentTargetsOnlyTheDedicatedFixtureWithTwoClosedExtras() {
        val intent = NotificationActionFixture.commandIntentForTest("reply", "post")

        assertEquals("co.twinotify.fixture", intent.component?.packageName)
        assertEquals("co.twinotify.fixture.FixtureCommandReceiver", intent.component?.className)
        assertEquals(setOf("fixture", "operation"), intent.extras?.keySet())
        assertEquals("reply", intent.getStringExtra("fixture"))
        assertEquals("post", intent.getStringExtra("operation"))
    }

    @Test
    fun commandIntentRejectsValuesOutsideTheFixedEnums() {
        for ((fixture, operation) in listOf(
            "arbitrary" to "post",
            "reply" to "arbitrary",
            "" to "post",
            "reply" to "",
        )) {
            assertFailsWith<IllegalArgumentException> {
                NotificationActionFixture.commandIntentForTest(fixture, operation)
            }
        }
    }

    @Test
    fun fixtureStateIsBoundedClosedAndContainsNoNotificationContent() {
        val state = NotificationActionFixture.sanitizeStateForTest(
            JSONObject()
                .put("reply_dispatch_count", 2)
                .put("mark_read_dispatch_count", 1)
                .put("last_fixture_generation", 4)
                .put("last_terminal_status", "mark_read_dispatched"),
        )

        assertEquals(
            setOf(
                "available",
                "reply_dispatch_count",
                "mark_read_dispatch_count",
                "last_fixture_generation",
                "last_terminal_status",
            ),
            state.keys().asSequence().toSet(),
        )
        assertTrue(state.getBoolean("available"))
        assertEquals(2, state.getInt("reply_dispatch_count"))
        assertFalse(state.toString().contains("reply_text", ignoreCase = true))
        assertFalse(state.toString().contains("title", ignoreCase = true))

        assertFailsWith<IllegalArgumentException> {
            NotificationActionFixture.sanitizeStateForTest(
                JSONObject()
                    .put("reply_dispatch_count", 1)
                    .put("mark_read_dispatch_count", 0)
                    .put("last_fixture_generation", 1)
                    .put("last_terminal_status", "reply_dispatched")
                    .put("title", "forbidden"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            NotificationActionFixture.sanitizeStateForTest(
                JSONObject()
                    .put("reply_dispatch_count", -1)
                    .put("mark_read_dispatch_count", 0)
                    .put("last_fixture_generation", 1)
                    .put("last_terminal_status", "reply_dispatched"),
            )
        }
    }
}
