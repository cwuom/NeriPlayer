package moe.ouom.neriplayer.data.settings

import android.content.Context
import android.content.SharedPreferences
import moe.ouom.neriplayer.core.player.download.DEFAULT_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.MAX_DOWNLOAD_PARALLELISM
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class BootstrapSettingsSnapshotTest {

    @Test
    fun `sanitized clears blank bootstrap values`() {
        val snapshot = BootstrapSettingsSnapshot(
            bypassProxy = false,
            downloadDirectoryUri = " ",
            downloadDirectoryLabel = "",
            downloadFileNameTemplate = " "
        ).sanitized()

        assertEquals(false, snapshot.bypassProxy)
        assertEquals(true, snapshot.youtubeEnabled)
        assertNull(snapshot.downloadDirectoryUri)
        assertNull(snapshot.downloadDirectoryLabel)
        assertNull(snapshot.downloadFileNameTemplate)
    }

    @Test
    fun `bootstrap snapshot preserves disabled YouTube state`() {
        val snapshot = BootstrapSettingsSnapshot(youtubeEnabled = false).sanitized()

        assertEquals(false, snapshot.youtubeEnabled)
    }

    @Test
    fun `bootstrap snapshot preserves high refresh preference`() {
        val snapshot = BootstrapSettingsSnapshot(preferHighRefreshRate = true).sanitized()

        assertEquals(true, snapshot.preferHighRefreshRate)
    }

    @Test
    fun `download quality startup state defaults to following playback`() {
        val snapshot = BootstrapSettingsSnapshot().sanitized()

        assertEquals(true, snapshot.downloadFollowPlaybackAudioQuality)
    }

    @Test
    fun `download quality startup state preserves independent selection`() {
        val snapshot = BootstrapSettingsSnapshot(
            downloadFollowPlaybackAudioQuality = false
        ).sanitized()

        assertEquals(false, snapshot.downloadFollowPlaybackAudioQuality)
    }

    @Test
    fun `download parallelism bootstrap value stays in the supported range`() {
        assertEquals(
            DEFAULT_DOWNLOAD_PARALLELISM,
            BootstrapSettingsSnapshot().sanitized().downloadParallelism
        )
        assertEquals(
            1,
            BootstrapSettingsSnapshot(downloadParallelism = 0).sanitized().downloadParallelism
        )
        assertEquals(
            MAX_DOWNLOAD_PARALLELISM,
            BootstrapSettingsSnapshot(downloadParallelism = Int.MAX_VALUE)
                .sanitized()
                .downloadParallelism
        )
    }

    @Test
    fun `legacy bootstrap snapshot without parallelism does not expose the default as persisted`() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(
            context.getSharedPreferences("bootstrap_settings_snapshot", Context.MODE_PRIVATE)
        ).thenReturn(preferences)
        `when`(preferences.getBoolean("ready", false)).thenReturn(true)
        `when`(preferences.contains("download_parallelism")).thenReturn(false)

        assertNull(readBootstrapDownloadParallelism(context))
    }

    @Test
    fun `cached bootstrap parallelism is normalized only when its key exists`() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(
            context.getSharedPreferences("bootstrap_settings_snapshot", Context.MODE_PRIVATE)
        ).thenReturn(preferences)
        `when`(preferences.getBoolean("ready", false)).thenReturn(true)
        `when`(preferences.contains("download_parallelism")).thenReturn(true)
        `when`(
            preferences.getInt("download_parallelism", DEFAULT_DOWNLOAD_PARALLELISM)
        ).thenReturn(MAX_DOWNLOAD_PARALLELISM + 1)

        assertEquals(MAX_DOWNLOAD_PARALLELISM, readBootstrapDownloadParallelism(context))
    }

    @Test
    fun `warmed snapshot does not overwrite a complete direct setting write`() {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(
            context.getSharedPreferences("bootstrap_settings_snapshot", Context.MODE_PRIVATE)
        ).thenReturn(preferences)
        `when`(preferences.getBoolean("ready", false)).thenReturn(true)
        `when`(preferences.contains("download_parallelism")).thenReturn(true)
        `when`(preferences.getInt("download_parallelism", DEFAULT_DOWNLOAD_PARALLELISM))
            .thenReturn(3)
        `when`(preferences.contains("download_follow_playback_audio_quality")).thenReturn(true)

        assertFalse(
            persistWarmedBootstrapSettingsSnapshot(
                context,
                BootstrapSettingsSnapshot(downloadParallelism = 6)
            )
        )
        verify(preferences, never()).edit()
    }
}
