package moe.ouom.neriplayer.data.sync.store.state

import android.content.Context
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.config.SyncPreferencesConfigSnapshot
import moe.ouom.neriplayer.data.model.config.WebDavSyncConfigSnapshot
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.preferences.PlayHistorySyncPreferences
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class SyncConfigurationStorageTest {
    @Test
    fun `configuration requires each credential and repository field`() {
        val prefs = MemorySyncPreferences()
        val github = SecureTokenStorage(prefs.preferences)
        assertEquals(GitHubSyncConfigSnapshot(), github.snapshot())
        github.saveToken("t")
        assertFalse(github.isConfigured())
        github.saveRepository("", "r")
        assertFalse(github.isConfigured())
        github.saveRepository("o", "r")
        assertTrue(github.isConfigured())
        github.saveToken("")
        assertFalse(github.isConfigured())
        github.clearAll()

        val webdav = WebDavStorage(prefs.preferences)
        assertEquals(WebDavSyncConfigSnapshot(), webdav.snapshot())
        for ((server, user, password) in listOf(
            Triple("", "u", "p"), Triple(" ", "u", "p"),
            Triple("https://example.test", "", "p"), Triple("https://example.test", " ", "p"),
            Triple("https://example.test", "u", ""), Triple("https://example.test", "u", " ")
        )) {
            webdav.saveConfiguration(server, user, password, "")
            assertFalse(webdav.isConfigured())
        }
        webdav.clearAll()
        prefs.values["server_url"] = "https://example.test"
        assertFalse(webdav.isConfigured())
        prefs.values["username"] = "u"
        assertFalse(webdav.isConfigured())
        prefs.values["password"] = "p"
        assertTrue(webdav.isConfigured())
    }

    @Test
    fun `github defaults and each configuration field round trip`() {
        val prefs = MemorySyncPreferences()
        val store = SecureTokenStorage(prefs.preferences)
        assertFalse(store.isConfigured())
        store.saveToken("t")
        assertFalse(store.isConfigured())
        store.saveRepository("o", "")
        assertFalse(store.isConfigured())
        store.saveRepository("o", "r")
        assertTrue(store.isConfigured())
        assertTrue(store.isDataSaverMode())
        assertFalse(store.isTokenWarningDismissed())
        store.setTokenWarningDismissed(true)
        store.setDataSaverMode(false)
        store.setAutoSyncEnabled(false)
        store.saveLastSyncTime(99)
        store.saveLastRemoteSha("sha")
        assertTrue(store.isTokenWarningDismissed())
        assertFalse(store.isAutoSyncEnabled())
        assertEquals(99L, store.getLastSyncTime())
        assertEquals("sha", store.getLastRemoteSha())
        assertEquals("t", store.snapshot().token)
        store.clearToken()
        assertFalse(store.isConfigured())
        val acknowledge = store.captureSyncMetadataGuard()
        store.clearAll()
        assertFalse(acknowledge { store.saveLastCompletedSyncTime(200L) })
        assertEquals(setOf("sync_configuration_generation"), prefs.values.keys)
        assertNull(store.getToken())
        assertNull(store.getRepoOwner())
        assertNull(store.getRepoName())
        assertNull(store.getLastRemoteSha())
        assertEquals(0L, store.getLastSyncTime())
        assertEquals(0L, store.getLastCompletedSyncTime())
    }

    @Test
    fun `github restore preserves device counter epoch and remote version`() {
        val prefs = MemorySyncPreferences(mapOf("device_id" to "existing", "sync_causal_counter" to 42L, "sync_mutation_version" to 9L, "last_remote_sha" to "sha", "play_history_update_mode" to "BATCHED"))
        val store = SecureTokenStorage(prefs.preferences)
        assertEquals("BATCHED", store.getLegacyPlayHistoryUpdateModeName())
        val configuration = GitHubSyncConfigSnapshot(token = "t", repoOwner = "o", repoName = "r", autoSyncEnabled = true, dataSaverMode = false)
        store.restore(configuration)
        assertEquals(configuration, store.snapshot())
        assertEquals("existing", store.getDeviceId())
        assertEquals(9L, store.getSyncMutationVersion())
        assertEquals(43L, store.nextSyncCausalTokens(1).single().counter)
        assertNull(store.getLegacyPlayHistoryUpdateModeName())
        assertEquals("sha", store.getLastRemoteSha())
        store.restore(GitHubSyncConfigSnapshot())
        assertNull(store.getToken())
    }

    @Test
    fun `webdav normalizes URL paths and restores configuration without clearing sync version`() {
        val prefs = MemorySyncPreferences()
        val store = WebDavStorage(prefs.preferences)
        assertFalse(store.isConfigured())
        assertNull(store.getRemoteFileUrl())
        store.saveConfiguration(" https://example.test/dav/ ", "user", "pass", " /music/cloud/ ")
        assertTrue(store.isConfigured())
        assertEquals("https://example.test/dav/music/cloud/neriplayer-sync.json", store.getRemoteFileUrl())
        assertEquals("pass", store.getPassword())
        store.saveLastSyncTime(77)
        store.saveLastRemoteFingerprint("fingerprint")
        store.setAutoSyncEnabled(false)
        val snapshot = store.snapshot()
        store.restore(WebDavSyncConfigSnapshot())
        assertFalse(store.isConfigured())
        assertEquals(77L, store.getLastSyncTime())
        assertEquals("fingerprint", store.getLastRemoteFingerprint())
        store.restore(snapshot)
        assertEquals(snapshot, store.snapshot())
        val acknowledge = store.captureSyncMetadataGuard()
        store.clearAll()
        assertFalse(acknowledge { store.saveLastCompletedSyncTime(200L) })
        assertEquals(setOf("sync_configuration_generation"), prefs.values.keys)
        assertNull(store.getServerUrl())
        assertNull(store.getUsername())
        assertNull(store.getPassword())
        assertEquals("", store.getBasePath())
        assertNull(store.getLastRemoteFingerprint())
        assertEquals(0L, store.getLastSyncTime())
        assertEquals(0L, store.getLastCompletedSyncTime())
    }

    @Test
    fun `persisted invalid webdav URLs remain available for configuration repair`() {
        for (server in listOf("not-a-url", "https://", "https://[::1", "ftp://example.test/dav")) {
            val prefs = MemorySyncPreferences(mapOf(
                "server_url" to server, "base_path" to "music", "username" to "user",
                "password" to "pass", "auto_sync_enabled" to false,
                "last_sync_time" to 77L, "last_remote_fingerprint" to "fingerprint"
            ))
            val store = WebDavStorage(prefs.preferences)
            val original = store.snapshot()
            val originalValues = prefs.values.toMap()

            assertFalse(store.isConfigured())

            assertEquals(original, store.snapshot())
            assertEquals(originalValues, prefs.values.toMap())
            assertEquals(77L, store.getLastSyncTime())
            assertEquals("fingerprint", store.getLastRemoteFingerprint())
        }
    }

    @Test
    fun `restored invalid webdav URLs are retained until the user repairs them`() {
        for (server in listOf("not-a-url", "ftp://example.test/dav")) {
            val prefs = MemorySyncPreferences()
            val store = WebDavStorage(prefs.preferences)
            val imported = WebDavSyncConfigSnapshot(server, "music", "user", "pass", false)
            store.restore(imported)

            assertFalse(store.isConfigured())
            assertEquals(imported, store.snapshot())
            val reopened = WebDavStorage(prefs.restart().preferences)
            assertFalse(reopened.isConfigured())
            assertEquals(imported, reopened.snapshot())

            reopened.saveConfiguration("https://example.test/dav", "user", "pass", "music")
            assertTrue(reopened.isConfigured())
            assertEquals("https://example.test/dav/music/neriplayer-sync.json", reopened.getRemoteFileUrl())
        }
    }

    @Test
    fun `valid webdav HTTP URLs preserve query fragment and encoded paths after restore`() {
        for ((server, remote) in listOf(
            "http://example.test/dav%2Fmusic?route=fixture%2Fv4#settings" to
                "http://example.test/dav%2Fmusic/a%20b/neriplayer-sync.json?route=fixture%2Fv4#settings",
            "https://example.test/dav%20music?route=fixture&selector=a%20b#settings" to
                "https://example.test/dav%20music/a%20b/neriplayer-sync.json?route=fixture&selector=a%20b#settings"
        )) {
            val prefs = MemorySyncPreferences()
            val store = WebDavStorage(prefs.preferences)
            val imported = WebDavSyncConfigSnapshot(server, "a b", "user", "pass", false)
            store.restore(imported)

            assertTrue(store.isConfigured())
            assertEquals(imported, store.snapshot())
            assertEquals(remote, store.getRemoteFileUrl())
            val reopened = WebDavStorage(prefs.restart().preferences)
            assertTrue(reopened.isConfigured())
            assertEquals(imported, reopened.snapshot())
            assertEquals(remote, reopened.getRemoteFileUrl())
        }
    }

    @Test
    fun `history interval migrates legacy once and explicit preference wins`() {
        val prefs = MemorySyncPreferences()
        val context = mock(Context::class.java)
        `when`(context.getSharedPreferences("play_history_sync_preferences", Context.MODE_PRIVATE)).thenReturn(prefs.preferences)
        val store = PlayHistorySyncPreferences(context)
        assertEquals(PlayHistorySyncPreferences.UpdateMode.EVERY_10_MINUTES, store.getUpdateMode("BATCHED"))
        assertEquals(600_000L, store.getUpdateMode().intervalMillis)
        assertEquals(PlayHistorySyncPreferences.UpdateMode.EVERY_10_MINUTES, store.getUpdateMode("BATCHED_30"))
        store.restore(SyncPreferencesConfigSnapshot(playHistoryUpdateMode = "EVERY_30_MINUTES"))
        assertEquals(1_800_000L, store.getUpdateMode().intervalMillis)
        store.restore(SyncPreferencesConfigSnapshot(playHistoryUpdateMode = "invalid"), "BATCHED_15")
        assertEquals("EVERY_15_MINUTES", store.snapshot().playHistoryUpdateMode)
        store.restore(SyncPreferencesConfigSnapshot(playHistoryUpdateMode = "invalid"))
        assertEquals(0L, store.getUpdateMode().intervalMillis)
    }
}
