package co.twinotify.core.filter

import android.content.Context
import android.content.ContextWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith

class AppFilterStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun defaultFilteredPackagesAreDeniedUntilExplicitlyAllowed() {
        val defaults = setOf("com.example.music")

        assertEquals(defaults, AppFilterPreferences().effective(defaults))
        assertEquals(
            emptySet(),
            AppFilterPreferences(explicitlyAllowed = defaults).effective(defaults),
        )
    }

    @Test
    fun explicitBlockWinsAndBlockingAgainClearsAnAllowOverride() {
        val allowed = AppFilterPreferences(
            explicitlyAllowed = setOf("com.example.music"),
        )
        val blockedAgain = allowed.block("com.example.music")

        assertEquals(setOf("com.example.music"), blockedAgain.effective(setOf("com.example.music")))
        assertEquals(emptySet(), blockedAgain.explicitlyAllowed)
    }

    @Test
    fun allowingAnExplicitlyBlockedAppRemovesItFromTheEffectiveSet() {
        val blocked = AppFilterPreferences(
            explicitlyDenied = setOf("com.example.chat"),
        )

        assertEquals(emptySet(), blocked.allow("com.example.chat").effective(emptySet()))
    }

    @Test
    fun removePublishesRemainingCommittedPackagesWithoutEmptyWindow() = runTest {
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = temporaryFolder.root
        }

        AppFilterStore.clear(context, emptySet())
        AppFilterStore.add(context, "pkg.a", emptySet())
        AppFilterStore.add(context, "pkg.b", emptySet())
        assertEquals(setOf("pkg.a", "pkg.b"), AppFilterStore.load(context, emptySet()))

        AppFilterStore.remove(context, "pkg.a", emptySet())

        assertEquals(setOf("pkg.b"), AppFilterStore.cachedOrEmpty())
    }

    @Test
    fun removeKeepsLastCommittedSnapshotVisibleUntilCommitCompletes() = runTest {
        var persisted = setOf("pkg.a", "pkg.b")
        val snapshot = AppFilterSnapshot()
        snapshot.load { persisted }
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()

        val removal = async {
            snapshot.mutate {
                commitEntered.complete(Unit)
                releaseCommit.await()
                (persisted - "pkg.a").also { persisted = it }
            }
        }
        commitEntered.await()

        assertEquals(setOf("pkg.a", "pkg.b"), snapshot.cachedOrEmpty())
        releaseCommit.complete(Unit)
        removal.await()
        assertEquals(setOf("pkg.b"), snapshot.cachedOrEmpty())
    }

    @Test
    fun addPublishesTheFullCommittedSet() = runTest {
        var persisted = setOf("pkg.a", "pkg.b")
        val snapshot = AppFilterSnapshot()
        snapshot.load { persisted }

        snapshot.mutate { (persisted + "pkg.c").also { persisted = it } }

        assertEquals(setOf("pkg.a", "pkg.b", "pkg.c"), snapshot.cachedOrEmpty())
    }

    @Test
    fun concurrentMutationsPublishTheFinalSerializedCommit() = runTest {
        var persisted = setOf("pkg.a", "pkg.b")
        val snapshot = AppFilterSnapshot()
        snapshot.load { persisted }
        val firstCommitEntered = CompletableDeferred<Unit>()
        val releaseFirstCommit = CompletableDeferred<Unit>()
        val secondCommitEntered = CompletableDeferred<Unit>()

        val add = async {
            snapshot.mutate {
                firstCommitEntered.complete(Unit)
                releaseFirstCommit.await()
                (persisted + "pkg.c").also { persisted = it }
            }
        }
        firstCommitEntered.await()
        val remove = async(start = CoroutineStart.UNDISPATCHED) {
            snapshot.mutate {
                secondCommitEntered.complete(Unit)
                (persisted - "pkg.a").also { persisted = it }
            }
        }

        assertFalse(secondCommitEntered.isCompleted)
        releaseFirstCommit.complete(Unit)
        add.await()
        remove.await()
        assertEquals(setOf("pkg.b", "pkg.c"), persisted)
        assertEquals(persisted, snapshot.cachedOrEmpty())
    }

    @Test
    fun persistenceFailureKeepsTheLastCommittedSnapshot() = runTest {
        val snapshot = AppFilterSnapshot()
        snapshot.load { setOf("pkg.a", "pkg.b") }

        assertFailsWith<IllegalStateException> {
            snapshot.mutate { error("storage unavailable") }
        }

        assertEquals(setOf("pkg.a", "pkg.b"), snapshot.cachedOrEmpty())
    }

    @Test
    fun clearPublishesEmptyOnlyAfterItsCommitSucceeds() = runTest {
        val snapshot = AppFilterSnapshot()
        snapshot.load { setOf("pkg.a", "pkg.b") }
        val commitEntered = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()

        val clear = async {
            snapshot.mutate {
                commitEntered.complete(Unit)
                releaseCommit.await()
                emptySet()
            }
        }
        commitEntered.await()

        assertEquals(setOf("pkg.a", "pkg.b"), snapshot.cachedOrEmpty())
        releaseCommit.complete(Unit)
        clear.await()
        assertEquals(emptySet(), snapshot.cachedOrEmpty())
    }

    @Test
    fun publishedSnapshotDoesNotAliasMutablePersistenceState() = runTest {
        val committed = linkedSetOf("pkg.a", "pkg.b")
        val snapshot = AppFilterSnapshot()

        snapshot.load { committed }
        committed.clear()

        assertEquals(setOf("pkg.a", "pkg.b"), snapshot.cachedOrEmpty())
    }
}
