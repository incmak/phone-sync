package co.twinotify.core.actions

import android.app.PendingIntent
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ActionInvokeIntentTest {
    @Test
    fun uriCarriesOnlyStableMirrorAndActionIdentity() {
        val uri = MirrorActionIntent.dataUri("mirror-tag", 41, ACTION_ID)

        assertEquals("twinotify://invoke/mirror-tag/41/$ACTION_ID", uri)
        assertEquals(
            ActionInvokeIdentity("mirror-tag", 41, ACTION_ID),
            MirrorActionIntent.parse(uri),
        )
    }

    @Test
    fun parserRejectsMalformedOrNonCanonicalIdentity() {
        assertNull(MirrorActionIntent.parse(null))
        assertNull(MirrorActionIntent.parse("https://invoke/mirror-tag/41/$ACTION_ID"))
        assertNull(MirrorActionIntent.parse("twinotify://invoke/mirror-tag/0/$ACTION_ID"))
        assertNull(MirrorActionIntent.parse("twinotify://invoke/mirror-tag/41/not-a-uuid"))
        assertNull(MirrorActionIntent.parse("twinotify://invoke/mirror-tag/41/${UUID.randomUUID()}/extra"))
    }

    @Test
    fun onlyReplyPendingIntentIsMutable() {
        val replyFlags = MirrorActionIntent.pendingIntentFlags(reply = true)
        val buttonFlags = MirrorActionIntent.pendingIntentFlags(reply = false)

        assertTrue(replyFlags and PendingIntent.FLAG_MUTABLE != 0)
        assertFalse(replyFlags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(buttonFlags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertFalse(buttonFlags and PendingIntent.FLAG_MUTABLE != 0)
    }

    @Test
    fun receiverTrustsUriAndRemoteInputOnlyAndActionsRequireAuthentication() {
        val root = File(requireNotNull(System.getProperty("user.dir")), "src/main/java/co/twinotify/core")
        val receiver = File(root, "actions/ActionInvokeReceiver.kt").readText()
        val poster = File(root, "service/MirrorPoster.kt").readText()

        assertTrue(receiver.contains("MirrorActionIntent.parse(intent?.dataString)"))
        assertTrue(receiver.contains("RemoteInput::getResultsFromIntent"))
        assertFalse(receiver.contains("getStringExtra"))
        assertFalse(receiver.contains("getIntExtra"))
        assertTrue(poster.contains(".setAuthenticationRequired(true)"))
        assertTrue(poster.contains(".setAllowGeneratedReplies(false)"))
    }

    private companion object {
        const val ACTION_ID = "33333333-3333-4333-8333-333333333333"
    }
}
