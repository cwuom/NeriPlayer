package moe.ouom.neriplayer.widget

import android.appwidget.AppWidgetManager
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

class PlaybackWidgetPortraitSizeTest {

    @Test
    fun `without host options the progress layout uses the full width default`() {
        assertEquals(PlaybackWidgetSize(250, 110), playbackWidgetSizeFromOptions(null, hasProgress = true))
    }

    @Test
    fun `without host options the compact layout uses the compact defaults`() {
        assertEquals(PlaybackWidgetSize(110, 110), playbackWidgetSizeFromOptions(null, hasProgress = false))
    }

    @Test
    fun `portrait size pairs the minimum width with the maximum height`() {
        val options = options(minWidth = 180, maxWidth = 320, minHeight = 60, maxHeight = 140)

        assertEquals(PlaybackWidgetSize(180, 140), playbackWidgetSizeFromOptions(options, hasProgress = false))
    }

    @Test
    fun `missing portrait values fall back to the landscape values`() {
        val options = options(minWidth = 0, maxWidth = 320, minHeight = 90, maxHeight = 0)

        assertEquals(
            PlaybackWidgetSize(320, 90),
            playbackWidgetSizeFromOptions(options, hasProgress = true, isStrip = true)
        )
    }

    @Test
    fun `empty strip options use the strip defaults`() {
        val options = options(minWidth = 0, maxWidth = 0, minHeight = 0, maxHeight = 0)

        assertEquals(
            PlaybackWidgetSize(250, 56),
            playbackWidgetSizeFromOptions(options, hasProgress = false, isStrip = true)
        )
    }

    @Test
    fun `oversized host values are capped`() {
        val options = options(minWidth = 4_000, maxWidth = 4_000, minHeight = 2_400, maxHeight = 2_400)

        assertEquals(PlaybackWidgetSize(1_000, 1_000), playbackWidgetSizeFromOptions(options, hasProgress = true))
    }

    @Test
    fun `a dimension uses the first positive candidate before the default`() {
        assertEquals(190, resolvePlaybackWidgetDimension(minDp = 0, maxDp = 190, defaultDp = 110))
        assertEquals(110, resolvePlaybackWidgetDimension(minDp = -20, maxDp = -1, defaultDp = 110))
    }

    private fun options(minWidth: Int, maxWidth: Int, minHeight: Int, maxHeight: Int): Bundle =
        mock(Bundle::class.java).also { options ->
            `when`(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)).thenReturn(minWidth)
            `when`(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0)).thenReturn(maxWidth)
            `when`(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)).thenReturn(minHeight)
            `when`(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0)).thenReturn(maxHeight)
        }
}
