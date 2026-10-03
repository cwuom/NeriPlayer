package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.config.GitHubSyncConfigSnapshot
import moe.ouom.neriplayer.data.model.config.WebDavSyncConfigSnapshot
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.testing.MemorySyncPreferences
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncCompletionTimeStorageTest {
    @Test
    fun `legacy checkpoint remains visible until a completion time is saved`() {
        val githubPrefs = MemorySyncPreferences(mapOf("last_sync_time" to 40L))
        val webdavPrefs = MemorySyncPreferences(mapOf("last_sync_time" to 70L))
        val github = SecureTokenStorage(githubPrefs.preferences)
        val webdav = WebDavStorage(webdavPrefs.preferences)

        assertEquals(40L, github.getLastCompletedSyncTime())
        assertEquals(70L, webdav.getLastCompletedSyncTime())
        assertFalse(githubPrefs.values.containsKey("last_completed_sync_time"))
        assertFalse(webdavPrefs.values.containsKey("last_completed_sync_time"))

        github.saveLastCompletedSyncTime(0L)
        webdav.saveLastCompletedSyncTime(0L)

        assertEquals(0L, github.getLastCompletedSyncTime())
        assertEquals(0L, webdav.getLastCompletedSyncTime())
        assertEquals(40L, github.getLastSyncTime())
        assertEquals(70L, webdav.getLastSyncTime())
    }

    @Test
    fun `completion time updates preserve merge checkpoints and remote versions`() {
        val github = SecureTokenStorage(MemorySyncPreferences().preferences)
        val webdav = WebDavStorage(MemorySyncPreferences().preferences)
        github.saveLastSyncTime(40L)
        github.saveLastRemoteSha("github-version")
        webdav.saveLastSyncTime(70L)
        webdav.saveLastRemoteFingerprint("webdav-version")

        github.saveLastCompletedSyncTime(100L)
        webdav.saveLastCompletedSyncTime(200L)

        assertEquals(100L, github.getLastCompletedSyncTime())
        assertEquals(200L, webdav.getLastCompletedSyncTime())
        assertEquals(40L, github.getLastSyncTime())
        assertEquals(70L, webdav.getLastSyncTime())
        assertEquals("github-version", github.getLastRemoteSha())
        assertEquals("webdav-version", webdav.getLastRemoteFingerprint())
    }

    @Test
    fun `completion times remain available after reopening persisted storage`() {
        val githubPrefs = MemorySyncPreferences(mapOf("last_sync_time" to 40L))
        val webdavPrefs = MemorySyncPreferences(mapOf("last_sync_time" to 70L))
        SecureTokenStorage(githubPrefs.preferences).saveLastCompletedSyncTime(100L)
        WebDavStorage(webdavPrefs.preferences).saveLastCompletedSyncTime(200L)

        assertEquals(100L, SecureTokenStorage(githubPrefs.restart().preferences).getLastCompletedSyncTime())
        assertEquals(200L, WebDavStorage(webdavPrefs.restart().preferences).getLastCompletedSyncTime())
    }

    @Test
    fun `github observers receive completion changes from another preferences wrapper`() = runTest {
        val prefs = MemorySyncPreferences(mapOf("last_sync_time" to 40L))
        val observed = SecureTokenStorage(WithoutNotifications(prefs.preferences))
        val writer = SecureTokenStorage(WithoutNotifications(prefs.preferences))
        val values = mutableListOf<Long>()
        backgroundScope.launch { observed.observeLastCompletedSyncTime().toList(values) }
        runCurrent()

        writer.saveLastCompletedSyncTime(100L)
        runCurrent()
        writer.saveLastCompletedSyncTime(100L)
        runCurrent()
        writer.saveLastCompletedSyncTime(200L)
        runCurrent()

        assertEquals(listOf(40L, 100L, 200L), values)
    }

    @Test
    fun `webdav observers receive completion changes from another preferences wrapper`() = runTest {
        val prefs = MemorySyncPreferences(mapOf("last_sync_time" to 70L))
        val observed = WebDavStorage(WithoutNotifications(prefs.preferences))
        val writer = WebDavStorage(WithoutNotifications(prefs.preferences))
        val values = mutableListOf<Long>()
        backgroundScope.launch { observed.observeLastCompletedSyncTime().toList(values) }
        runCurrent()

        writer.saveLastCompletedSyncTime(100L)
        runCurrent()
        writer.saveLastCompletedSyncTime(100L)
        runCurrent()
        writer.saveLastCompletedSyncTime(200L)
        runCurrent()

        assertEquals(listOf(70L, 100L, 200L), values)
    }

    @Test
    fun `github and webdav completion observations retain independent values`() = runTest {
        val github = SecureTokenStorage(MemorySyncPreferences(mapOf("last_sync_time" to 40L)).preferences)
        val webdav = WebDavStorage(MemorySyncPreferences(mapOf("last_sync_time" to 70L)).preferences)
        val githubValues = mutableListOf<Long>()
        val webdavValues = mutableListOf<Long>()
        backgroundScope.launch { github.observeLastCompletedSyncTime().toList(githubValues) }
        backgroundScope.launch { webdav.observeLastCompletedSyncTime().toList(webdavValues) }
        runCurrent()

        github.saveLastCompletedSyncTime(100L)
        runCurrent()
        assertEquals(listOf(40L, 100L), githubValues)
        assertEquals(listOf(70L), webdavValues)

        webdav.saveLastCompletedSyncTime(200L)
        runCurrent()
        assertEquals(listOf(40L, 100L), githubValues)
        assertEquals(listOf(70L, 200L), webdavValues)
    }

    @Test
    fun `legacy observations follow checkpoints only until a completion time exists`() = runTest {
        val github = SecureTokenStorage(MemorySyncPreferences(mapOf("last_sync_time" to 40L)).preferences)
        val webdav = WebDavStorage(MemorySyncPreferences(mapOf("last_sync_time" to 70L)).preferences)
        val githubValues = mutableListOf<Long>()
        val webdavValues = mutableListOf<Long>()
        backgroundScope.launch { github.observeLastCompletedSyncTime().toList(githubValues) }
        backgroundScope.launch { webdav.observeLastCompletedSyncTime().toList(webdavValues) }
        runCurrent()

        github.saveLastSyncTime(50L)
        webdav.saveLastSyncTime(80L)
        runCurrent()
        github.saveLastCompletedSyncTime(100L)
        webdav.saveLastCompletedSyncTime(200L)
        runCurrent()
        github.saveLastSyncTime(60L)
        webdav.saveLastSyncTime(90L)
        runCurrent()

        assertEquals(listOf(40L, 50L, 100L), githubValues)
        assertEquals(listOf(70L, 80L, 200L), webdavValues)
    }

    @Test
    fun `clearing either backend clears persisted completion and notifies another instance`() = runTest {
        val githubPrefs = MemorySyncPreferences()
        val webdavPrefs = MemorySyncPreferences()
        val github = SecureTokenStorage(WithoutNotifications(githubPrefs.preferences))
        val webdav = WebDavStorage(WithoutNotifications(webdavPrefs.preferences))
        github.saveLastSyncTime(40L)
        github.saveLastCompletedSyncTime(100L)
        webdav.saveLastSyncTime(70L)
        webdav.saveLastCompletedSyncTime(200L)
        val githubValues = mutableListOf<Long>()
        val webdavValues = mutableListOf<Long>()
        backgroundScope.launch { github.observeLastCompletedSyncTime().toList(githubValues) }
        backgroundScope.launch { webdav.observeLastCompletedSyncTime().toList(webdavValues) }
        runCurrent()

        SecureTokenStorage(WithoutNotifications(githubPrefs.preferences)).clearAll()
        WebDavStorage(WithoutNotifications(webdavPrefs.preferences)).clearAll()
        runCurrent()

        assertEquals(listOf(100L, 0L), githubValues)
        assertEquals(listOf(200L, 0L), webdavValues)
        assertFalse(githubPrefs.values.containsKey("last_completed_sync_time"))
        assertFalse(webdavPrefs.values.containsKey("last_completed_sync_time"))
        assertEquals(0L, SecureTokenStorage(githubPrefs.restart().preferences).getLastCompletedSyncTime())
        assertEquals(0L, WebDavStorage(webdavPrefs.restart().preferences).getLastCompletedSyncTime())
    }

    @Test
    fun `restoring configuration preserves completion timestamps`() {
        val github = SecureTokenStorage(MemorySyncPreferences().preferences)
        val webdav = WebDavStorage(MemorySyncPreferences().preferences)
        github.saveLastCompletedSyncTime(100L)
        webdav.saveLastCompletedSyncTime(200L)

        github.restore(GitHubSyncConfigSnapshot())
        webdav.restore(WebDavSyncConfigSnapshot())

        assertEquals(100L, github.getLastCompletedSyncTime())
        assertEquals(200L, webdav.getLastCompletedSyncTime())
    }

    private class WithoutNotifications(delegate: SharedPreferences) : SharedPreferences by delegate {
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) = Unit
    }
}
