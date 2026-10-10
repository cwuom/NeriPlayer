package moe.ouom.neriplayer.data.settings.bootstrap

import android.os.Looper
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.settings.bootstrap.BootstrapSettingsSnapshot
import moe.ouom.neriplayer.data.model.settings.download.DEFAULT_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.data.settings.IsolatedSettingsDataStore
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.testing.InMemorySharedPreferencesRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic

class BootstrapSettingsStartupReadTest {
    private val preferences = InMemorySharedPreferencesRegistry()
    private val context = IsolatedSettingsDataStore.context(preferences)
    private val mirror get() = preferences[BOOTSTRAP_MIRROR]

    @Test
    fun `complete startup mirror is served without rewriting it`() {
        persistBootstrapSettingsSnapshot(
            context,
            BootstrapSettingsSnapshot(
                bypassProxy = false,
                youtubeEnabled = false,
                downloadDirectoryUri = TREE_URI,
                downloadDirectoryLabel = "Music",
                downloadParallelism = 2
            )
        )
        val commits = mirror.commitCount

        val snapshot = readBootstrapSettingsSnapshotSync(context)

        assertFalse(snapshot.bypassProxy)
        assertFalse(snapshot.youtubeEnabled)
        assertEquals(TREE_URI, snapshot.downloadDirectoryUri)
        assertEquals("Music", snapshot.downloadDirectoryLabel)
        assertEquals(2, snapshot.downloadParallelism)
        assertEquals(commits, mirror.commitCount)
    }

    @Test
    fun `incomplete mirror on the main thread is returned with defaults for missing values`() {
        mirror.edit()
            .putBoolean("ready", true)
            .putBoolean("bypass_proxy", false)
            .putBoolean("prefer_high_refresh_rate", true)
            .commit()

        // JVM 桩里的 Looper.myLooper() 与 getMainLooper() 都为 null, 等价于主线程
        val snapshot = readBootstrapSettingsSnapshotSync(context)

        assertFalse(snapshot.bypassProxy)
        assertTrue(snapshot.youtubeEnabled)
        assertTrue(snapshot.preferHighRefreshRate)
        assertTrue(snapshot.downloadFollowPlaybackAudioQuality)
        assertEquals(DEFAULT_DOWNLOAD_PARALLELISM, snapshot.downloadParallelism)
    }

    @Test
    fun `missing mirror off the main thread is rebuilt from settings`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = {
                it[SettingsKeys.BYPASS_PROXY] = false
                it[SettingsKeys.DOWNLOAD_DIRECTORY_URI] = TREE_URI
                it[SettingsKeys.DOWNLOAD_DIRECTORY_LABEL] = "Music"
            }
        ) {
            val snapshot = readOffMainThread()

            assertFalse(snapshot.bypassProxy)
            assertTrue(snapshot.youtubeEnabled)
            assertEquals(TREE_URI, snapshot.downloadDirectoryUri)
            assertEquals("Music", snapshot.downloadDirectoryLabel)
            assertEquals(true, mirror.snapshot()["ready"])
            assertEquals(snapshot, readBootstrapSettingsSnapshotSync(context))
        }
    }

    @Test
    fun `incomplete mirror off the main thread is replaced by settings`() = runTest {
        IsolatedSettingsDataStore.withEmptySettings(
            context,
            seed = { it[SettingsKeys.YOUTUBE_ENABLED] = false }
        ) {
            mirror.edit()
                .putBoolean("ready", true)
                .putBoolean("youtube_enabled", true)
                .commit()

            val snapshot = readOffMainThread()

            assertFalse(snapshot.youtubeEnabled)
            assertEquals(false, mirror.snapshot()["youtube_enabled"])
            assertEquals(DEFAULT_DOWNLOAD_PARALLELISM, mirror.snapshot()["download_parallelism"])
            assertEquals(true, mirror.snapshot()["download_follow_playback_audio_quality"])
        }
    }

    @Test
    fun `settings without bootstrap keys map to startup defaults`() {
        val snapshot = emptyPreferences().toBootstrapSettingsSnapshot()

        assertTrue(snapshot.bypassProxy)
        assertTrue(snapshot.youtubeEnabled)
        assertFalse(snapshot.preferHighRefreshRate)
        assertNull(snapshot.downloadDirectoryUri)
        assertNull(snapshot.downloadDirectoryLabel)
        assertTrue(snapshot.downloadFollowPlaybackAudioQuality)
        assertEquals(DEFAULT_DOWNLOAD_PARALLELISM, snapshot.downloadParallelism)
    }

    @Test
    fun `explicit settings map onto the startup snapshot`() {
        val snapshot = preferencesOf(
            SettingsKeys.BYPASS_PROXY to false,
            SettingsKeys.YOUTUBE_ENABLED to false,
            SettingsKeys.PREFER_HIGH_REFRESH_RATE to true,
            SettingsKeys.DOWNLOAD_DIRECTORY_URI to TREE_URI,
            SettingsKeys.DOWNLOAD_DIRECTORY_LABEL to " "
        ).toBootstrapSettingsSnapshot()

        assertFalse(snapshot.bypassProxy)
        assertFalse(snapshot.youtubeEnabled)
        assertTrue(snapshot.preferHighRefreshRate)
        assertEquals(TREE_URI, snapshot.downloadDirectoryUri)
        assertNull(snapshot.downloadDirectoryLabel)
    }

    @Test
    fun `sanitized keeps meaningful download directory values`() {
        val snapshot = BootstrapSettingsSnapshot(
            downloadDirectoryUri = TREE_URI,
            downloadDirectoryLabel = "Music"
        ).sanitized()

        assertEquals(TREE_URI, snapshot.downloadDirectoryUri)
        assertEquals("Music", snapshot.downloadDirectoryLabel)
    }

    private fun readOffMainThread(): BootstrapSettingsSnapshot {
        return mockStatic(Looper::class.java).use { looper ->
            looper.`when`<Looper> { Looper.myLooper() }.thenReturn(mock(Looper::class.java))
            readBootstrapSettingsSnapshotSync(context)
        }
    }

    private companion object {
        const val BOOTSTRAP_MIRROR = "bootstrap_settings_snapshot"
        const val TREE_URI = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
    }
}
