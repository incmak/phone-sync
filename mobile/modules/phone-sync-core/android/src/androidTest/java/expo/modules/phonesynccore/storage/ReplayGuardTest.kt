package expo.modules.phonesynccore.storage

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class ReplayGuardTest {

    @Test
    fun firstSightingFalseReplayTrue() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ReplayGuard.clear(ctx)
        val now = System.currentTimeMillis()
        assertFalse(ReplayGuard.seenOrMark(ctx, "msg-1", now), "first sighting should return false")
        assertTrue(ReplayGuard.seenOrMark(ctx, "msg-1", now + 1_000), "repeat within TTL should return true")
    }

    @Test
    fun expiredEntryAcceptedAgain() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ReplayGuard.clear(ctx)
        val now = System.currentTimeMillis()
        assertFalse(ReplayGuard.seenOrMark(ctx, "msg-2", now), "first sighting should return false")
        // Simulate >48h elapsed — entry should be expired and accepted again
        val future = now + 49L * 60 * 60 * 1_000
        assertFalse(ReplayGuard.seenOrMark(ctx, "msg-2", future), "expired entry should return false")
    }

    @Test
    fun distinctIdsAreIndependent() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ReplayGuard.clear(ctx)
        val now = System.currentTimeMillis()
        assertFalse(ReplayGuard.seenOrMark(ctx, "id-A", now))
        assertFalse(ReplayGuard.seenOrMark(ctx, "id-B", now), "different id should be independent")
        assertTrue(ReplayGuard.seenOrMark(ctx, "id-A", now + 500), "id-A should still be seen")
    }

    @Test
    fun clearResetsAllEntries() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ReplayGuard.clear(ctx)
        val now = System.currentTimeMillis()
        ReplayGuard.seenOrMark(ctx, "msg-clear", now)
        assertTrue(ReplayGuard.seenOrMark(ctx, "msg-clear", now + 1_000), "should be seen before clear")
        ReplayGuard.clear(ctx)
        assertFalse(ReplayGuard.seenOrMark(ctx, "msg-clear", now + 2_000), "after clear should return false")
    }
}
