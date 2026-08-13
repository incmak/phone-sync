package co.twinotify.core.e2e

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class E2eControlSecurityTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun wrongSessionTokenCannotExecuteCommand() {
        val receiver = E2eControlReceiver()
        val result = receiver.executeForTest(
            context,
            E2eCommand(requestId = "wrong-token", name = "STATUS", token = "wrong"),
        )
        assertEquals("unauthorized", result.code)
    }

    @Test
    fun missingSessionTokenCannotExecuteCommand() {
        val result = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(requestId = "missing-token", name = "STATUS"),
        )
        assertEquals("unauthorized", result.code)
    }

    @Test
    fun unknownCommandIsRejectedAfterAuthentication() {
        val token = E2eSessionToken.forTest(context, "allowlisted")
        val result = E2eControlReceiver().executeForTest(
            context,
            E2eCommand(requestId = "unknown", name = "SHELL", token = token),
        )
        assertEquals("forbidden", result.code)
    }

    @Test
    fun stateQueryContainsNoNotificationContent() {
        val token = E2eSessionToken.forTest(context, "state-query")
        val uri = E2eStateProvider.STATE_URI.buildUpon().appendQueryParameter("token", token).build()
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        assertNotNull(cursor)
        cursor.use {
            assertTrue(it.moveToFirst())
            val state = it.getString(it.getColumnIndexOrThrow("state_json"))
            assertFalse(state.contains("title", ignoreCase = true))
            assertFalse(state.contains("text", ignoreCase = true))
            assertFalse(state.contains("ciphertext", ignoreCase = true))
            assertFalse(state.contains("nonce", ignoreCase = true))
            assertFalse(state.contains("canonical_id", ignoreCase = true))
        }
    }

    @Test
    fun missingStateTokenCannotReadProvider() {
        assertFailsWith<SecurityException> {
            context.contentResolver.query(E2eStateProvider.STATE_URI, null, null, null, null)
        }
    }

    @Test
    fun wrongStateTokenCannotReadProvider() {
        val uri = E2eStateProvider.STATE_URI.buildUpon().appendQueryParameter("token", "wrong").build()
        assertFailsWith<SecurityException> {
            context.contentResolver.query(uri, null, null, null, null)
        }
    }

    @Test
    fun tokenIsInstallScopedAndPersistedOnlyThroughRunAsFile() {
        val first = E2eSessionToken.ensure(context)
        val second = E2eSessionToken.ensure(context)
        assertEquals(first, second)
        val tokenFile = context.getFileStreamPath("e2e-token")
        assertTrue(tokenFile.exists())
        assertEquals(first, tokenFile.readText())
    }

    @Test
    fun controlIntentUsesAuthenticatedDebugAction() {
        val intent = Intent(E2eControlReceiver.ACTION_CONTROL)
        assertEquals(E2eControlReceiver.ACTION_CONTROL, intent.action)
        assertEquals("co.twinotify.app.e2e", E2eStateProvider.AUTHORITY)
    }
}
