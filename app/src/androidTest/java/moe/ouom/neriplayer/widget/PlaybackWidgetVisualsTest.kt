package moe.ouom.neriplayer.widget

import android.graphics.Bitmap
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaybackWidgetVisualsTest {
    @Test
    fun modernThemeBackgroundsFillEveryEdgeWithoutTransparentPixels() {
        val artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(172, 95, 72))
        }

        val visuals = buildPlaybackWidgetVisuals(artwork)

        assertOpaqueEdges(visuals.themeBackground)
        assertOpaqueEdges(visuals.compactThemeBackground)
    }

    @Test
    fun legacyThemeBackgroundsKeepTheExpectedRoundedCorners() {
        val artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(172, 95, 72))
        }

        val visuals = buildPlaybackWidgetVisuals(artwork)

        assertRoundedEdges(visuals.legacyThemeBackground)
        assertRoundedEdges(visuals.legacyCompactThemeBackground)
    }

    @Test
    fun themeBackgroundSelectionUsesTheCompatibleRendererForEachApiRange() {
        val artwork = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.rgb(172, 95, 72))
        }
        val visuals = buildPlaybackWidgetVisuals(artwork)

        assertSame(
            visuals.themeBackground,
            selectPlaybackWidgetThemeBackground(visuals, hasProgress = true, sdkInt = 31),
        )
        assertSame(
            visuals.compactThemeBackground,
            selectPlaybackWidgetThemeBackground(visuals, hasProgress = false, sdkInt = 31),
        )
        assertSame(
            visuals.legacyThemeBackground,
            selectPlaybackWidgetThemeBackground(visuals, hasProgress = true, sdkInt = 28),
        )
        assertSame(
            visuals.legacyCompactThemeBackground,
            selectPlaybackWidgetThemeBackground(visuals, hasProgress = false, sdkInt = 28),
        )
    }

    @Test
    fun fallbackBackgroundIsHiddenUntilTheUpdaterNeedsIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val inflater = LayoutInflater.from(context)

        listOf(R.layout.widget_playback_2x2, R.layout.widget_playback_4x2).forEach { layoutRes ->
            val root = inflater.inflate(layoutRes, null)
            assertEquals(View.GONE, root.findViewById<View>(R.id.widget_fallback_background).visibility)
        }
    }

    private fun assertOpaqueEdges(bitmap: Bitmap?) {
        assertNotNull(bitmap)
        val image = requireNotNull(bitmap)
        val edgePoints = listOf(
            0 to 0,
            image.width - 1 to 0,
            0 to image.height - 1,
            image.width - 1 to image.height - 1,
            image.width / 2 to 0,
            image.width / 2 to image.height - 1,
            0 to image.height / 2,
            image.width - 1 to image.height / 2,
        )

        edgePoints.forEach { (x, y) ->
            assertEquals(255, Color.alpha(image.getPixel(x, y)))
        }
    }

    private fun assertRoundedEdges(bitmap: Bitmap?) {
        assertNotNull(bitmap)
        val image = requireNotNull(bitmap)

        assertEquals(0, Color.alpha(image.getPixel(0, 0)))
        assertEquals(0, Color.alpha(image.getPixel(image.width - 1, image.height - 1)))
        assertEquals(255, Color.alpha(image.getPixel(image.width / 2, 0)))
        assertEquals(255, Color.alpha(image.getPixel(0, image.height / 2)))
    }
}
