package co.twinotify.core.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.json.JSONArray
import org.json.JSONObject

class HistoryContentTest {
    @Test
    fun extractsOnlyBoundedTitleAndPreviewFromNotificationPayload() {
        val payload = notificationPayload(
            title = "T".repeat(200),
            text = "fallback",
            conversationMessage = "P".repeat(400),
        )

        val content = requireNotNull(HistoryContentCodec.fromNotificationPayload(payload))

        assertEquals(120, content.title?.length)
        assertEquals(240, content.preview?.length)
        assertTrue(content.preview!!.all { it == 'P' })
    }

    @Test
    fun rejectsNonNotificationPayloadAndOmitsEmptyContent() {
        assertNull(HistoryContentCodec.fromNotificationPayload("{\"type\":\"notif.cancel\"}"))
        assertNull(HistoryContentCodec.fromNotificationPayload(notificationPayload(null, null, null)))
    }

    @Test
    fun opaqueAppGroupDoesNotExposePackageName() {
        val token = historyAppGroupId("example.messages")

        assertEquals(64, token.length)
        assertTrue(token.matches(Regex("[0-9a-f]{64}")))
        assertTrue(!token.contains("example"))
    }

    private fun notificationPayload(
        title: String?,
        text: String?,
        conversationMessage: String?,
    ): String = JSONObject().apply {
        put("type", "notif.post")
        put("canon_id", "canon")
        put("package_name", "example.messages")
        put("id", 7)
        put("title", title ?: JSONObject.NULL)
        put("text", text ?: JSONObject.NULL)
        put("visibility", "private")
        put("is_group_summary", false)
        put("is_ongoing", false)
        put("is_clearable", true)
        put("ts", 1_000)
        if (conversationMessage != null) {
            put("conversation", JSONObject().apply {
                put("is_group", false)
                put("messages", JSONArray().put(JSONObject().apply {
                    put("text", conversationMessage)
                    put("timestamp", 1_000)
                }))
            })
        }
    }.toString()
}
