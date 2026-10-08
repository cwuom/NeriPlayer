package moe.ouom.neriplayer.data.sync.store.state

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
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SyncTargetCheckpointTest {
    @Test
    fun `switching the github repository drops the previous repository checkpoint`() = runTest {
        val github = syncedGitHub()
        val observed = mutableListOf<Long>()
        backgroundScope.launch { github.observeLastCompletedSyncTime().toList(observed) }
        runCurrent()

        github.saveRepository("owner", "other-music")
        runCurrent()

        assertGitHubCheckpointCleared(github)
        assertEquals(listOf(100L, 0L), observed)
    }

    @Test
    fun `saving the same github repository or a new token keeps its checkpoint`() {
        val github = syncedGitHub()

        github.saveRepository(" Owner ", "MUSIC")
        github.saveToken("rotated-token")
        github.restore(github.snapshot().copy(token = "restored-token"))

        assertEquals(40L, github.getLastSyncTime())
        assertEquals("sha-a", github.getLastRemoteSha())
        assertEquals(100L, github.getLastCompletedSyncTime())
    }

    @Test
    fun `restoring another github repository drops the previous repository checkpoint`() {
        val github = syncedGitHub()

        github.restore(github.snapshot().copy(repoName = "other-music"))

        assertGitHubCheckpointCleared(github)
    }

    @Test
    fun `switching the webdav server, path or account drops the previous target checkpoint`() {
        listOf<WebDavStorage.() -> Unit>(
            { saveConfiguration("https://other.example.com/dav", "alice", "secret", "music") },
            { saveConfiguration("https://dav.example.com/dav", "alice", "secret", "other") },
            { saveConfiguration("https://dav.example.com/dav", "bob", "secret", "music") },
            { restore(snapshot().copy(basePath = "other")) }
        ).forEach { switchTarget ->
            val webdav = syncedWebDav()

            webdav.switchTarget()

            assertEquals(0L, webdav.getLastSyncTime())
            assertNull(webdav.getLastRemoteFingerprint())
            assertEquals(0L, webdav.getLastCompletedSyncTime())
        }
    }

    @Test
    fun `saving the same webdav target or a new password keeps its checkpoint`() {
        val webdav = syncedWebDav()

        webdav.saveConfiguration(" https://dav.example.com/dav/ ", "alice", "rotated", "/music/")
        webdav.restore(webdav.snapshot().copy(password = "restored"))

        assertEquals(70L, webdav.getLastSyncTime())
        assertEquals("fingerprint-a", webdav.getLastRemoteFingerprint())
        assertEquals(200L, webdav.getLastCompletedSyncTime())
    }

    @Test
    fun `a checkpoint kept across an empty restore still belongs only to its original target`() {
        val github = syncedGitHub()
        val webdav = syncedWebDav()
        val webdavTarget = webdav.snapshot()

        github.restore(GitHubSyncConfigSnapshot())
        webdav.restore(WebDavSyncConfigSnapshot())
        github.saveRepository("owner", "music")
        webdav.restore(webdavTarget)

        assertEquals("sha-a", github.getLastRemoteSha())
        assertEquals("fingerprint-a", webdav.getLastRemoteFingerprint())

        github.restore(GitHubSyncConfigSnapshot())
        webdav.restore(WebDavSyncConfigSnapshot())
        github.saveRepository("owner", "other-music")
        webdav.saveConfiguration("https://other.example.com/dav", "alice", "secret", "music")

        assertGitHubCheckpointCleared(github)
        assertEquals(0L, webdav.getLastSyncTime())
        assertNull(webdav.getLastRemoteFingerprint())
        assertEquals(0L, webdav.getLastCompletedSyncTime())
    }

    @Test
    fun `existing installs attribute their checkpoint to the stored target`() {
        val github = SecureTokenStorage(
            MemorySyncPreferences(
                mapOf("repo_owner" to "owner", "repo_name" to "music", "last_sync_time" to 40L, "last_remote_sha" to "sha-a")
            ).preferences
        )
        val webdav = WebDavStorage(
            MemorySyncPreferences(
                mapOf(
                    "server_url" to "https://dav.example.com/dav", "base_path" to "music", "username" to "alice",
                    "last_sync_time" to 70L, "last_remote_fingerprint" to "fingerprint-a"
                )
            ).preferences
        )

        github.saveRepository("owner", "music")
        webdav.saveConfiguration("https://dav.example.com/dav", "alice", "rotated", "music")
        assertEquals("sha-a", github.getLastRemoteSha())
        assertEquals("fingerprint-a", webdav.getLastRemoteFingerprint())

        github.saveRepository("owner", "other-music")
        webdav.saveConfiguration("https://dav.example.com/dav", "bob", "secret", "music")
        assertGitHubCheckpointCleared(github)
        assertNull(webdav.getLastRemoteFingerprint())
    }

    @Test
    fun `a session captured before the switch cannot restore the old checkpoint`() {
        val github = syncedGitHub()
        val webdav = syncedWebDav()
        val githubGuard = github.captureSyncMetadataGuard()
        val webdavGuard = webdav.captureSyncMetadataGuard()

        github.saveRepository("owner", "other-music")
        webdav.saveConfiguration("https://other.example.com/dav", "alice", "secret", "music")

        assertFalse(githubGuard { github.saveLastRemoteSha("sha-a") })
        assertFalse(webdavGuard { webdav.saveLastRemoteFingerprint("fingerprint-a") })
        assertGitHubCheckpointCleared(github)
        assertNull(webdav.getLastRemoteFingerprint())
    }

    private fun syncedGitHub(): SecureTokenStorage = SecureTokenStorage(MemorySyncPreferences().preferences).apply {
        restore(GitHubSyncConfigSnapshot(token = "token", repoOwner = "owner", repoName = "music"))
        saveLastSyncTime(40L)
        saveLastRemoteSha("sha-a")
        saveLastCompletedSyncTime(100L)
    }

    private fun syncedWebDav(): WebDavStorage = WebDavStorage(MemorySyncPreferences().preferences).apply {
        saveConfiguration("https://dav.example.com/dav", "alice", "secret", "music")
        saveLastSyncTime(70L)
        saveLastRemoteFingerprint("fingerprint-a")
        saveLastCompletedSyncTime(200L)
    }

    private fun assertGitHubCheckpointCleared(github: SecureTokenStorage) {
        assertEquals(0L, github.getLastSyncTime())
        assertNull(github.getLastRemoteSha())
        assertEquals(0L, github.getLastCompletedSyncTime())
    }
}
