package co.twinotify.core.call

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Registration/permission seam only; this test never claims a real cellular call. */
@RunWith(AndroidJUnit4::class)
class TelephonyCallStateSourceTest {
    @Test
    fun registrationIsPermissionBoundAndClosable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = TelephonyCallStateSource(context)
        val capabilities = source.capabilities()
        assertNotNull(capabilities)
        if (!capabilities.permissionGranted) {
            assertFailsWith<IllegalArgumentException> { source.register {} }
            return
        }
        if (!capabilities.supported) return
        var registration: AutoCloseable? = null
        try {
            registration = source.register { /* no call content is observed */ }
            assertTrue(true)
        } finally {
            registration?.close()
        }
    }
}
