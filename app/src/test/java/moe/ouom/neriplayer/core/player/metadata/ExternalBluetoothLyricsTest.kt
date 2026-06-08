package moe.ouom.neriplayer.core.player.metadata

import android.media.AudioDeviceInfo
import moe.ouom.neriplayer.ui.component.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalBluetoothLyricsTest {
    @Test
    fun `findExternalBluetoothLyricLine applies offset and trims blank lines`() {
        val lyrics = listOf(
            LyricEntry(" intro ", startTimeMs = 1_000L, endTimeMs = 1_500L),
            LyricEntry("verse", startTimeMs = 2_000L, endTimeMs = 3_000L),
            LyricEntry("   ", startTimeMs = 4_000L, endTimeMs = 5_000L)
        )

        assertNull(findExternalBluetoothLyricLine(lyrics, 500L))
        assertEquals("intro", findExternalBluetoothLyricLine(lyrics, 1_000L))
        assertEquals("verse", findExternalBluetoothLyricLine(lyrics, 1_500L, 600L))
        assertNull(findExternalBluetoothLyricLine(lyrics, 4_500L))
    }

    @Test
    fun `shouldUseExternalBluetoothLyrics requires enabled bluetooth device and lyric line`() {
        assertTrue(
            shouldUseExternalBluetoothLyrics(
                enabled = true,
                audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                lyricLine = "current line"
            )
        )
        assertFalse(
            shouldUseExternalBluetoothLyrics(
                enabled = false,
                audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                lyricLine = "current line"
            )
        )
        assertFalse(
            shouldUseExternalBluetoothLyrics(
                enabled = true,
                audioDeviceType = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
                lyricLine = "current line"
            )
        )
        assertFalse(
            shouldUseExternalBluetoothLyrics(
                enabled = true,
                audioDeviceType = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                lyricLine = " "
            )
        )
    }

    @Test
    fun `resolveExternalBluetoothMetadataText puts lyric in title and song info in artist`() {
        val metadata = resolveExternalBluetoothMetadataText(
            normalTitle = "Song",
            normalArtist = "Artist",
            lyricLine = "current line",
            useBluetoothLyrics = true
        )

        assertEquals("current line", metadata.title)
        assertEquals("Song - Artist", metadata.artist)
        assertEquals("current line", metadata.displayTitle)
        assertEquals("Song - Artist", metadata.displaySubtitle)
    }
}
