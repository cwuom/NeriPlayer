package moe.ouom.neriplayer.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackWidgetStateTest {
    @Test
    fun `progress clamps invalid and overflow positions`() {
        assertEquals(0, playbackWidgetProgress(positionMs = 10_000L, durationMs = 0L))
        assertEquals(0, playbackWidgetProgress(positionMs = -10_000L, durationMs = 180_000L))
        assertEquals(
            PLAYBACK_WIDGET_PROGRESS_MAX,
            playbackWidgetProgress(positionMs = 240_000L, durationMs = 180_000L),
        )
    }

    @Test
    fun `playing position is bucketed to a one second widget cadence`() {
        assertEquals(
            44_000L,
            playbackWidgetBucketedPositionMs(
                positionMs = 44_999L,
                durationMs = 180_000L,
                isPlaying = true,
            ),
        )
    }

    @Test
    fun `paused position keeps exact visible time`() {
        assertEquals(
            44_999L,
            playbackWidgetBucketedPositionMs(
                positionMs = 44_999L,
                durationMs = 180_000L,
                isPlaying = false,
            ),
        )
    }

    @Test
    fun `time text uses compact music duration format`() {
        assertEquals("0:00", formatPlaybackWidgetTime(-1L))
        assertEquals("3:05", formatPlaybackWidgetTime(185_000L))
        assertEquals("1:02:03", formatPlaybackWidgetTime(3_723_000L))
    }

    @Test
    fun `cached artwork requires a live song and a ready bitmap`() {
        val ready = buildPlaybackWidgetState(
            title = "Wall",
            subtitle = "Jing Guo",
            status = "Playing",
            positionMs = 30_000L,
            durationMs = 180_000L,
            hasSong = true,
            isPlaying = true,
            isFavorite = false,
            canToggleFavorite = true,
            isFloatingLyricsEnabled = false,
            artworkReady = true,
        )
        val notReady = ready.copy(artworkReady = false)
        val idle = ready.copy(hasSong = false)

        assertTrue(shouldUseCachedPlaybackWidgetArtwork(ready))
        assertFalse(shouldUseCachedPlaybackWidgetArtwork(notReady))
        assertFalse(shouldUseCachedPlaybackWidgetArtwork(idle))
    }

    @Test
    fun `new cover keeps the previous visual while artwork is loading`() {
        val loading = buildPlaybackWidgetState(
            title = "Wall",
            subtitle = "Jing Guo",
            status = "Playing",
            positionMs = 30_000L,
            durationMs = 180_000L,
            hasSong = true,
            isPlaying = true,
            isFavorite = false,
            canToggleFavorite = true,
            isFloatingLyricsEnabled = false,
            artworkReady = false,
            artworkPending = true,
        )

        assertTrue(shouldRetainPlaybackWidgetVisuals(loading))
        assertFalse(shouldRetainPlaybackWidgetVisuals(loading.copy(artworkPending = false)))
        assertFalse(shouldRetainPlaybackWidgetVisuals(loading.copy(hasSong = false)))
    }

    @Test
    fun `matching visible metadata still refreshes for a new track or cover`() {
        val first = buildPlaybackWidgetState(
            title = "Same title",
            subtitle = "Same artist",
            status = "Paused",
            positionMs = 0L,
            durationMs = 180_000L,
            hasSong = true,
            isPlaying = false,
            isFavorite = false,
            canToggleFavorite = true,
            isFloatingLyricsEnabled = false,
            artworkReady = true,
            contentId = "song-a",
            coverId = "cover-a",
        )

        assertNotEquals(first, first.copy(contentId = "song-b"))
        assertNotEquals(first, first.copy(coverId = "cover-b"))
    }

    @Test
    fun `widget theme remains cover colored while preserving readable contrast`() {
        val colors = derivePlaybackWidgetThemeColors(0xFFF07B2A.toInt())

        assertEquals(0xFF, colors.backgroundStart ushr 24)
        assertEquals(0xFF, colors.backgroundEnd ushr 24)
        assertEquals(0xFF, colors.primaryControl ushr 24)
        assertTrue(channelBrightness(colors.backgroundStart) > channelBrightness(colors.backgroundEnd))
        assertTrue(colors.primaryControl != colors.backgroundStart)
    }

    @Test
    fun `cover theme suppresses the static widget fallback background`() {
        assertFalse(shouldShowPlaybackWidgetFallbackBackground(hasThemeBackground = true))
        assertTrue(shouldShowPlaybackWidgetFallbackBackground(hasThemeBackground = false))
    }

    private fun channelBrightness(color: Int): Int {
        return (color ushr 16 and 0xFF) +
            (color ushr 8 and 0xFF) +
            (color and 0xFF)
    }
}
