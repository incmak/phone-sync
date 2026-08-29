package co.twinotify.core.listener

import co.twinotify.core.protocol.ProtocolFixtures
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class NotifPostJsonTest {
    @Test
    fun legacyPayload_defaultsToAutoCancelWithoutActions() {
        val post = NotifPostJson.fromPayloadJson(
            ProtocolFixtures.readPath("v2-valid/notif-post-legacy-valid.json"),
        )

        assertTrue(post.is_auto_cancel)
        assertEquals(emptyList(), post.actions)
    }

    @Test
    fun currentPayload_preservesStrictActionDescriptorsAndRoundTrips() {
        val raw = ProtocolFixtures.readPath("v2-valid/notif-post-actions-valid.json")
        val post = NotifPostJson.fromPayloadJson(raw)

        assertTrue(post.is_auto_cancel)
        assertEquals(1, post.actions.size)
        assertEquals(
            NotifActionJson(
                action_id = "b6d3142a-e936-4d7d-b15a-bdf318bb0539",
                title = "Reply",
                semantic = 1,
                reply = true,
                reply_label = "Message",
            ),
            post.actions.single(),
        )
        val encoded = JSONObject(NotifPostBuilder.toPayloadJson(post))
        assertTrue(encoded.getBoolean("is_auto_cancel"))
        assertEquals("Reply", encoded.getJSONArray("actions").getJSONObject(0).getString("title"))
        assertFalse(encoded.getJSONArray("actions").getJSONObject(0).has("remote_input_key"))
    }

    @Test
    fun actionDescriptors_rejectOverflowUnknownKeysBoundsAndBadUuids() {
        val valid = JSONObject(ProtocolFixtures.readPath("v2-valid/notif-post-actions-valid.json"))
        val validAction = valid.getJSONArray("actions").getJSONObject(0)
        val fourActions = JSONArray().apply {
            repeat(4) { index ->
                put(JSONObject(validAction.toString()).put("action_id", UUID.randomUUID().toString()).put("title", "Action $index"))
            }
        }

        val invalid = listOf(
            JSONObject(valid.toString()).put("actions", fourActions),
            JSONObject(valid.toString()).put(
                "actions",
                JSONArray().put(JSONObject(validAction.toString()).put("remote_input_key", "private")),
            ),
            JSONObject(valid.toString()).put(
                "actions",
                JSONArray().put(JSONObject(validAction.toString()).put("title", "x".repeat(65))),
            ),
            JSONObject(valid.toString()).put(
                "actions",
                JSONArray().put(JSONObject(validAction.toString()).put("reply_label", "x".repeat(65))),
            ),
            JSONObject(valid.toString()).put(
                "actions",
                JSONArray().put(JSONObject(validAction.toString()).put("action_id", "not-a-uuid")),
            ),
        )

        invalid.forEach { payload ->
            assertFailsWith<IllegalArgumentException> {
                NotifPostJson.fromPayloadJson(payload.toString())
            }
        }
    }

    @Test
    fun actionDescriptor_allowsMissingOrNullReplyLabel() {
        val valid = JSONObject(ProtocolFixtures.readPath("v2-valid/notif-post-actions-valid.json"))
        val withoutLabel = valid.getJSONArray("actions").getJSONObject(0).apply { remove("reply_label") }
        val parsedWithout = NotifPostJson.fromPayloadJson(valid.toString())
        assertNull(parsedWithout.actions.single().reply_label)

        withoutLabel.put("reply_label", JSONObject.NULL)
        val parsedNull = NotifPostJson.fromPayloadJson(valid.toString())
        assertNull(parsedNull.actions.single().reply_label)
    }
}
