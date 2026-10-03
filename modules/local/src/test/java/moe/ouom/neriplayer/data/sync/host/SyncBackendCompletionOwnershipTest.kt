package moe.ouom.neriplayer.data.sync.host

import android.content.SharedPreferences
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.api.sync.github.GitHubApiClient
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.archive.SyncArchiveRepository
import moe.ouom.neriplayer.data.sync.github.GitHubSyncBackend
import moe.ouom.neriplayer.data.sync.remote.SyncRemoteSnapshotDecoder
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncBackend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(Parameterized::class)
class SyncBackendCompletionOwnershipTest(private val provider: String) {
    @get:Rule val temporary = TemporaryFolder()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun providers(): List<Array<String>> = listOf(arrayOf("github"), arrayOf("webdav"))
    }

    @Test
    fun `cleared configuration cannot be revived by an older backend acknowledgement`() = runTest {
        val fixture = fixture()
        val old = fixture.capture()
        val observed = mutableListOf<Long>()
        backgroundScope.launch { fixture.observe().toList(observed) }
        runCurrent()

        fixture.clear()
        runCurrent()
        assertFalse(old.complete("stale-version", 900L))
        runCurrent()

        assertEquals(Metadata(0L, 0L, null), fixture.persistedMetadata())
        assertEquals(listOf(70L, 0L), observed)
    }

    @Test
    fun `clear between remote version and checkpoint saves rejects the remaining acknowledgement`() = runTest {
        val fixture = fixture()
        val old = fixture.capture()
        val observed = mutableListOf<Long>()
        backgroundScope.launch { fixture.observe().toList(observed) }
        runCurrent()

        old.remoteVersion("stale-version")
        fixture.clear()
        runCurrent()
        old.checkpoint(900L)
        assertFalse(old.completed(900L))
        runCurrent()

        assertEquals(Metadata(0L, 0L, null), fixture.persistedMetadata())
        assertEquals(listOf(70L, 0L), observed)
    }

    @Test
    fun `reconfiguring the same address after clear does not revive an old backend`() = runTest {
        val fixture = fixture()
        val old = fixture.capture()
        val observed = mutableListOf<Long>()
        backgroundScope.launch { fixture.observe().toList(observed) }
        runCurrent()

        fixture.clear()
        fixture.configureOriginal()
        runCurrent()
        assertFalse(old.complete("stale-version", 900L))
        runCurrent()

        assertEquals(Metadata(0L, 0L, null), fixture.persistedMetadata())
        assertEquals(listOf(70L, 0L), observed)

        assertTrue(fixture.capture().complete("current-version", 1_000L))
        runCurrent()
        assertFalse(old.complete("stale-again", 1_100L))
        runCurrent()

        assertEquals(Metadata(1_000L, 1_000L, "current-version"), fixture.persistedMetadata())
        assertEquals(listOf(70L, 0L, 1_000L), observed)
    }

    @Test
    fun `changing the target preserves historical metadata and rejects the old backend`() {
        val fixture = fixture()
        val old = fixture.capture()

        fixture.changeTarget()
        assertFalse(old.complete("stale-version", 900L))

        assertEquals(Metadata(40L, 70L, "previous-version"), fixture.persistedMetadata())
        assertTrue(fixture.capture().complete("current-version", 1_000L))
        assertEquals(Metadata(1_000L, 1_000L, "current-version"), fixture.persistedMetadata())
    }

    @Test
    fun `changing credentials on the same target invalidates the old backend`() {
        val fixture = fixture()
        val old = fixture.capture()

        fixture.changeCredentials()
        assertFalse(old.complete("stale-version", 900L))

        assertEquals(Metadata(40L, 70L, "previous-version"), fixture.persistedMetadata())
        assertTrue(fixture.capture().complete("current-version", 1_000L))
        assertEquals(Metadata(1_000L, 1_000L, "current-version"), fixture.persistedMetadata())
    }

    @Test
    fun `restoring the original configuration preserves history but invalidates old acknowledgements`() {
        val fixture = fixture()
        val old = fixture.capture()

        fixture.restoreOriginal()
        assertFalse(old.complete("stale-version", 900L))

        assertEquals(Metadata(40L, 70L, "previous-version"), fixture.persistedMetadata())
        assertTrue(fixture.capture().complete("current-version", 1_000L))
        assertEquals(Metadata(1_000L, 1_000L, "current-version"), fixture.persistedMetadata())
    }

    @Test
    fun `a current backend can advance completion without advancing the merge checkpoint`() {
        val fixture = fixture()

        assertTrue(fixture.capture().completed(900L))

        assertEquals(Metadata(40L, 900L, "previous-version"), fixture.persistedMetadata())
    }

    private fun fixture(): Fixture = when (provider) {
        "github" -> GitHubFixture()
        "webdav" -> WebDavFixture()
        else -> error("Unknown provider")
    }.also { it.configureOriginal(); it.seedMetadata() }

    private data class Metadata(val checkpoint: Long, val completed: Long, val remoteVersion: String?)

    private class Writes(
        val remoteVersion: (String) -> Unit,
        val checkpoint: (Long) -> Unit,
        val completed: (Long) -> Boolean
    ) {
        fun complete(version: String, timestamp: Long): Boolean {
            remoteVersion(version)
            checkpoint(timestamp)
            return completed(timestamp)
        }
    }

    private abstract class Fixture {
        val preferences = RamDiskPreferences()
        abstract fun capture(): Writes
        abstract fun observe(): Flow<Long>
        abstract fun clear()
        abstract fun configureOriginal()
        abstract fun changeTarget()
        abstract fun changeCredentials()
        abstract fun restoreOriginal()
        abstract fun seedMetadata()
        abstract fun persistedMetadata(): Metadata
    }

    private inner class GitHubFixture : Fixture() {
        private fun storage(prefs: SharedPreferences = preferences.wrapper()): SecureTokenStorage =
            SecureTokenStorage::class.java.getDeclaredConstructor(
                SharedPreferences::class.java, File::class.java, Function1::class.java
            ).newInstance(prefs, null, null)

        override fun capture(): Writes {
            val store = storage()
            val backend = GitHubSyncBackend(
                store, mock(GitHubApiClient::class.java), store.getRepoOwner().orEmpty(), store.getRepoName().orEmpty(),
                SyncRemoteSnapshotDecoder { it }, { IOException("invalid backup") }, {},
                SyncArchiveRepository(temporary.newFolder())
            )
            return Writes(
                { backend.saveRemoteVersion(GitHubSyncBackend.Version(it, SyncArchiveRepository.MANIFEST_FILE_NAME)) },
                { backend.saveSyncTime(it) },
                { backend.saveCompletedSyncTime(it) }
            )
        }

        override fun observe(): Flow<Long> = storage().observeLastCompletedSyncTime()
        override fun clear() = storage().clearAll()
        override fun configureOriginal() {
            storage().saveToken("original-credential")
            storage().saveRepository("owner", "repo")
        }
        override fun changeTarget() = storage().saveRepository("other-owner", "other-repo")
        override fun changeCredentials() = storage().saveToken("replacement-credential")
        override fun restoreOriginal() {
            val store = storage()
            store.restore(store.snapshot())
        }
        override fun seedMetadata() {
            val store = storage()
            store.saveLastRemoteSha("previous-version")
            store.saveLastSyncTime(40L)
            store.saveLastCompletedSyncTime(70L)
        }
        override fun persistedMetadata(): Metadata {
            val store = storage(preferences.restart().wrapper())
            return Metadata(store.getLastSyncTime(), store.getLastCompletedSyncTime(), store.getLastRemoteSha())
        }
    }

    private inner class WebDavFixture : Fixture() {
        private fun storage(prefs: SharedPreferences = preferences.wrapper()): WebDavStorage =
            WebDavStorage::class.java.getDeclaredConstructor(SharedPreferences::class.java).newInstance(prefs)

        override fun capture(): Writes {
            val store = storage()
            val backend = WebDavSyncBackend(
                store, mock(WebDavApiClient::class.java), checkNotNull(store.getRemoteFileUrl()),
                SyncRemoteSnapshotDecoder { it }, { IOException("invalid backup") }, {},
                SyncArchiveRepository(temporary.newFolder())
            )
            return Writes(
                { backend.saveRemoteVersion(WebDavSyncBackend.Version(null, false, lastKnownFingerprint = it)) },
                { backend.saveSyncTime(it) },
                { backend.saveCompletedSyncTime(it) }
            )
        }

        override fun observe(): Flow<Long> = storage().observeLastCompletedSyncTime()
        override fun clear() = storage().clearAll()
        override fun configureOriginal() = storage().saveConfiguration(
            "https://example.test/dav", "user", "original-credential", "music"
        )
        override fun changeTarget() = storage().saveConfiguration(
            "https://other.test/dav", "user", "original-credential", "other-music"
        )
        override fun changeCredentials() = storage().saveConfiguration(
            "https://example.test/dav", "user", "replacement-credential", "music"
        )
        override fun restoreOriginal() {
            val store = storage()
            store.restore(store.snapshot())
        }
        override fun seedMetadata() {
            val store = storage()
            store.saveLastRemoteFingerprint("previous-version")
            store.saveLastSyncTime(40L)
            store.saveLastCompletedSyncTime(70L)
        }
        override fun persistedMetadata(): Metadata {
            val store = storage(preferences.restart().wrapper())
            return Metadata(store.getLastSyncTime(), store.getLastCompletedSyncTime(), store.getLastRemoteFingerprint())
        }
    }

    private class RamDiskPreferences(initial: Map<String, Any> = emptyMap()) {
        private val values = ConcurrentHashMap(initial)
        private val durableValues = ConcurrentHashMap(initial)

        fun restart(): RamDiskPreferences = synchronized(values) { RamDiskPreferences(durableValues.toMap()) }

        fun wrapper(): SharedPreferences = mock(SharedPreferences::class.java) { call ->
            val key = call.arguments.firstOrNull() as? String
            when (call.method.name) {
                "getString", "getLong", "getBoolean" -> values[key] ?: call.arguments[1]
                "contains" -> values.containsKey(key)
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> null
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            var clear = false
            return mock(SharedPreferences.Editor::class.java) { call ->
                when (call.method.name) {
                    "putString", "putLong", "putBoolean" -> {
                        changes[call.arguments[0] as String] = call.arguments[1]
                        call.mock
                    }
                    "remove" -> { changes[call.arguments[0] as String] = null; call.mock }
                    "clear" -> { clear = true; call.mock }
                    "commit", "apply" -> synchronized(values) {
                        if (clear) values.clear()
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        changes.clear()
                        clear = false
                        durableValues.clear()
                        durableValues.putAll(values)
                        if (call.method.name == "commit") true else null
                    }
                    else -> call.mock
                }
            }
        }
    }
}
