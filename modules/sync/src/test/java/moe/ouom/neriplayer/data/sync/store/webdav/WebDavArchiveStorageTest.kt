package moe.ouom.neriplayer.data.sync.store.webdav

import java.io.IOException
import moe.ouom.neriplayer.api.sync.webdav.WebDavApiClient
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcCandidate
import moe.ouom.neriplayer.data.sync.remote.WebDavArchiveGcState
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import org.junit.Assert.*
import org.junit.Test
import okhttp3.OkHttpClient

class WebDavArchiveStorageTest {
    private val manifestUrl = "https://sync.test/dav/neriplayer-sync-v3.manifest"
    private val target = scope(manifestUrl, "account")
    private val state = WebDavArchiveGcState(wallMs = 1000, uptimeMs = 100,
        candidates = listOf(WebDavArchiveGcCandidate("neriplayer-sync-v4-${"a".repeat(64)}.zst", "\"etag\"", 1)))

    @Test fun `locking capability and candidate age survive reopen only for the same target and account`() {
        val prefs = MemorySyncPreferences()
        val storage = WebDavStorage(prefs.preferences)
        storage.saveConfiguration("https://sync.test", "account", "password", "dav")
        storage.rememberArchiveLock(target)
        storage.saveArchiveGcState(target, state)
        val reopened = WebDavStorage(prefs.restart().preferences)
        assertTrue(reopened.archiveLockSupported(target))
        assertEquals(state, reopened.archiveGcState(target))
        assertFalse(reopened.archiveLockSupported(scope("https://sync.test/other/manifest", "account")))
        reopened.saveConfiguration("https://sync.test", "another-account", "password", "dav")
        val otherTarget = scope(manifestUrl, "another-account")
        assertFalse(reopened.archiveLockSupported(otherTarget))
        assertEquals(WebDavArchiveGcState(), reopened.archiveGcState(otherTarget))
        assertTrue(reopened.archiveLockSupported(target))
        assertEquals(state, reopened.archiveGcState(target))
    }

    @Test fun `a captured account scope stays fixed while active preferences change`() {
        val prefs = MemorySyncPreferences()
        val storage = WebDavStorage(prefs.preferences)
        val client = WebDavApiClient("old-account", "password", OkHttpClient(), "auth")
        val captured = client.archiveMaintenanceScope(manifestUrl)
        storage.saveConfiguration("https://sync.test", "new-account", "password", "dav")

        storage.rememberArchiveLock(captured)
        storage.saveArchiveGcState(captured, state)

        assertEquals(captured, client.archiveMaintenanceScope(manifestUrl))
        assertTrue(storage.archiveLockSupported(captured))
        assertEquals(state, storage.archiveGcState(captured))
        val current = scope(manifestUrl, "new-account")
        assertFalse(storage.archiveLockSupported(current))
        assertEquals(WebDavArchiveGcState(), storage.archiveGcState(current))
    }

    @Test fun `maintenance identity ignores fragments but keeps query routing and the captured account`() {
        val client = WebDavApiClient("account", "password", OkHttpClient(), "auth")
        assertEquals(client.archiveMaintenanceScope(manifestUrl), client.archiveMaintenanceScope("$manifestUrl#display"))
        assertNotEquals(client.archiveMaintenanceScope(manifestUrl), client.archiveMaintenanceScope("$manifestUrl?route=fixture"))
    }

    @Test fun `failed capability persistence prevents durable GC capability`() {
        val prefs = MemorySyncPreferences()
        prefs.failNextCommit = true
        assertThrows(IOException::class.java) { WebDavStorage(prefs.preferences).rememberArchiveLock(target) }
        assertFalse(WebDavStorage(prefs.restart().preferences).archiveLockSupported(target))
    }

    @Test fun `failed journal persistence cannot preserve an observation across restart`() {
        val prefs = MemorySyncPreferences()
        prefs.failNextCommit = true
        assertThrows(IOException::class.java) { WebDavStorage(prefs.preferences).saveArchiveGcState(target, state) }
        assertEquals(WebDavArchiveGcState(), WebDavStorage(prefs.restart().preferences).archiveGcState(target))
    }

    @Test fun `corrupt unknown future and oversized journals reset candidate age`() {
        val prefs = MemorySyncPreferences()
        val storage = WebDavStorage(prefs.preferences)
        storage.saveArchiveGcState(target, state)
        val key = prefs.values.keys.single { it.endsWith("_gc") }
        for (raw in listOf("broken", "null", "{}", "{\"version\":2,\"candidates\":[]}", "x".repeat(512 * 1024 + 1))) {
            prefs.values[key] = raw
            assertEquals(WebDavArchiveGcState(), storage.archiveGcState(target))
        }
        assertThrows(IllegalArgumentException::class.java) { storage.saveArchiveGcState(target, state.copy(version = 2)) }
    }

    private fun scope(url: String, account: String): String =
        WebDavApiClient(account, "password", OkHttpClient(), "auth").archiveMaintenanceScope(url)
}
