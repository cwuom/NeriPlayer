package moe.ouom.neriplayer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
