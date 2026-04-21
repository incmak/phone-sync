package co.twinotify.core.storage

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertFailsWith
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(AndroidJUnit4::class)
class NotificationMapDaoTest {

    private lateinit var db: NotificationDbImpl
    private lateinit var dao: NotificationMapDao

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, NotificationDbImpl::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.notificationMapDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun roundTrip_withTag() = runBlocking {
        dao.putMirror("canon1", "devA", 100, "mirror")
        assertEquals("canon1", dao.lookupByLocal(100, "mirror"), "lookupByLocal should return canon_id")
        assertEquals("devA", dao.lookupOrigin("canon1"), "lookupOrigin should return origin_device_id")
    }

    @Test
    fun roundTrip_nullTag() = runBlocking {
        dao.putMirror("canon2", "devB", 200, null)
        assertEquals("canon2", dao.lookupByLocal(200, null), "null tag lookup should return canon_id")
        assertEquals("devB", dao.lookupOrigin("canon2"), "lookupOrigin should return origin_device_id")
    }

    @Test
    fun deleteByCanonId_removesAllRows() = runBlocking {
        dao.putMirror("canon1", "devA", 100, "mirror")
        dao.deleteByCanonId("canon1")
        assertNull(dao.lookupByLocal(100, "mirror"), "local mapping should be gone after deleteByCanonId")
        assertNull(dao.lookupOrigin("canon1"), "mirror row should be gone after deleteByCanonId")
    }

    @Test
    fun sweepExpired_cutoffInFuture_deletesAllRows() = runBlocking {
        dao.putMirror("canon3", "devC", 300, "tag3")
        dao.putMirror("canon4", "devD", 400, "tag4")
        val cutoff = System.currentTimeMillis() + 60_000L // 1 minute in the future
        dao.sweepExpired(cutoff)
        assertNull(dao.lookupByLocal(300, "tag3"), "row canon3 should be swept")
        assertNull(dao.lookupByLocal(400, "tag4"), "row canon4 should be swept")
    }

    @Test
    fun sweepExpired_cutoffInPast_keepsAllRows() = runBlocking {
        dao.putMirror("canon5", "devE", 500, "tag5")
        val cutoff = System.currentTimeMillis() - 60_000L // 1 minute in the past
        dao.sweepExpired(cutoff)
        assertEquals("canon5", dao.lookupByLocal(500, "tag5"), "row should survive when cutoff is in the past")
        assertEquals("devE", dao.lookupOrigin("canon5"), "mirror row should survive when cutoff is in the past")
    }

    @Test
    fun duplicateLocalKey_throwsConstraintException() = runBlocking {
        dao.putMirror("canon1", "devA", 100, "mirror")
        // Second putMirror with SAME (localId, localTag) but different canonId
        // must throw SQLiteConstraintException (via Room's ABORT strategy).
        assertFailsWith<android.database.sqlite.SQLiteConstraintException> {
            dao.putMirror("canon2", "devB", 100, "mirror")
        }
        // Rollback check: canon2 MUST NOT have been committed to either table.
        assertNull(dao.lookupOrigin("canon2"), "canon2 should not exist — transaction should have rolled back")
        // And canon1's original mapping is still intact:
        assertEquals("canon1", dao.lookupByLocal(100, "mirror"))
        assertEquals("devA",   dao.lookupOrigin("canon1"))
    }

    @Test
    fun deleteByCanonId_cascadesToChildTable() = runBlocking {
        dao.putMirror("canon1", "devA", 100, "mirror")
        dao.deleteByCanonId("canon1")
        assertNull(dao.lookupOrigin("canon1"))
        assertNull(dao.lookupByLocal(100, "mirror"), "child row should be cascaded")
    }
}
