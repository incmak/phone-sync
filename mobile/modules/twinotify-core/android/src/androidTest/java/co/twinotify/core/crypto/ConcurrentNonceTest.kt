package co.twinotify.core.crypto

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.*

@RunWith(AndroidJUnit4::class)
class ConcurrentNonceTest {
    @Test fun exhaustedOrIncompleteStorageFailsWithoutChangingDurableNonceState(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = java.io.File(context.cacheDir, "nonce-test-${java.util.UUID.randomUUID()}.preferences_pb")
        val job = SupervisorJob()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(job + Dispatchers.IO), produceFile = { file })
        val prefixKey = byteArrayPreferencesKey("prefix")
        val counterKey = longPreferencesKey("counter")
        try {
            store.edit { it[prefixKey] = ByteArray(16) { 7 }; it[counterKey] = Long.MAX_VALUE }
            assertFailsWith<IllegalStateException> { NonceSource.next(store) }
            assertEquals(Long.MAX_VALUE, store.data.first()[counterKey])
            store.edit { it.remove(counterKey) }
            assertFailsWith<IllegalStateException> { NonceSource.next(store) }
            assertNull(store.data.first()[counterKey])
            assertContentEquals(ByteArray(16) { 7 }, store.data.first()[prefixKey])
        } finally { job.cancelAndJoin(); file.delete() }
    }

    @Test fun concurrentFirstAllocationsShareOnePrefixAndNeverResetTheCounter(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(context.packageName == "co.twinotify.core.test")
        // Isolated test APK only: exercise the first-allocation race deliberately.
        NonceSource.regenerate(context)
        val nonces = (1..128).map { async(Dispatchers.IO) { NonceSource.next(context) } }.awaitAll()
        assertEquals(1, nonces.map { it.copyOfRange(0, 16).toList() }.distinct().size)
        assertEquals((1L..128L).toList(), nonces.map { ByteBuffer.wrap(it, 16, 8).long }.sorted())
        val resumed = NonceSource.next(context)
        assertContentEquals(nonces.first().copyOfRange(0, 16), resumed.copyOfRange(0, 16))
        assertEquals(129L, ByteBuffer.wrap(resumed, 16, 8).long)
    }
}
