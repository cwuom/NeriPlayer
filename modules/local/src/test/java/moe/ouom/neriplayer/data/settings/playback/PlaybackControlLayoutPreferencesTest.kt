package moe.ouom.neriplayer.data.settings.playback

import moe.ouom.neriplayer.data.model.settings.playback.NowPlayingControlPlacement
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.data.settings.defaultPlaybackControlLayoutPreferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackControlLayoutPreferencesTest {
    @Test
    fun `missing values restore the current layout defaults`() {
        assertEquals(
            PlaybackControlLayoutPreferences(),
            resolvePlaybackControlLayoutPreferences(
                nowPlayingPlacementValue = null,
                nowPlayingSizeValue = null,
                lyricsSizeValue = null
            )
        )
    }

    @Test
    fun `tablet defaults place controls and progress together without changing phone defaults`() {
        assertEquals(
            PlaybackControlLayoutPreferences(),
            defaultPlaybackControlLayoutPreferences(smallestScreenWidthDp = 599)
        )
        val defaults = defaultPlaybackControlLayoutPreferences(smallestScreenWidthDp = 600)
        assertEquals(
            PlaybackControlLayoutPreferences(nowPlayingPlacement = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS),
            resolvePlaybackControlLayoutPreferences(null, null, null, defaults)
        )
    }

    @Test
    fun `tablet defaults keep every saved placement including the previous default`() {
        val defaults = defaultPlaybackControlLayoutPreferences(smallestScreenWidthDp = 800)
        NowPlayingControlPlacement.entries.forEach { placement ->
            val preferences = resolvePlaybackControlLayoutPreferences(
                nowPlayingPlacementValue = placement.ordinal,
                nowPlayingSizeValue = PlaybackControlSize.SMALL.ordinal,
                lyricsSizeValue = PlaybackControlSize.LARGE.ordinal,
                defaults = defaults
            )

            assertEquals(placement, preferences.nowPlayingPlacement)
            assertEquals(PlaybackControlSize.SMALL, preferences.nowPlayingSize)
            assertEquals(PlaybackControlSize.LARGE, preferences.lyricsSize)
        }
    }

    @Test
    fun `invalid tablet placement falls back to the device default`() {
        val defaults = defaultPlaybackControlLayoutPreferences(smallestScreenWidthDp = 600)
        listOf(-1, Int.MAX_VALUE).forEach { value ->
            assertEquals(
                defaults,
                resolvePlaybackControlLayoutPreferences(value, null, null, defaults)
            )
        }
    }

    @Test
    fun `invalid persisted values fall back without changing valid values`() {
        assertEquals(
            PlaybackControlLayoutPreferences(
                nowPlayingPlacement = NowPlayingControlPlacement.LOWER,
                nowPlayingSize = PlaybackControlSize.LARGE,
                lyricsSize = PlaybackControlSize.MEDIUM
            ),
            resolvePlaybackControlLayoutPreferences(
                nowPlayingPlacementValue = Int.MAX_VALUE,
                nowPlayingSizeValue = PlaybackControlSize.LARGE.ordinal,
                lyricsSizeValue = PlaybackControlSize.MEDIUM.ordinal
            )
        )
    }

    @Test
    fun `bottom with progress restores a continuous bottom playback region`() {
        val preferences = resolvePlaybackControlLayoutPreferences(
            nowPlayingPlacementValue = NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS.ordinal,
            nowPlayingSizeValue = PlaybackControlSize.MEDIUM.ordinal,
            lyricsSizeValue = PlaybackControlSize.MEDIUM.ordinal
        )

        assertEquals(NowPlayingControlPlacement.BOTTOM_WITH_PROGRESS, preferences.nowPlayingPlacement)
        assertTrue(preferences.nowPlayingPlacement.placesControlsAtBottom)
        assertTrue(preferences.nowPlayingPlacement.placesProgressAtBottom)
        assertFalse(NowPlayingControlPlacement.BOTTOM.placesProgressAtBottom)
    }
}
