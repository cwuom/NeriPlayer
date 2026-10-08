package moe.ouom.neriplayer.widget

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.set
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaybackWidgetVisualsTest {

    @Test
    fun `blur source is a tiny crop matching the widget aspect`() {
        val artwork = solidBitmap(192, 192, Color.RED)

        val wide = playbackWidgetBlurSource(artwork, targetWidth = 1024, targetHeight = 512)
        val tall = playbackWidgetBlurSource(artwork, targetWidth = 300, targetHeight = 600)
        val square = playbackWidgetBlurSource(artwork, targetWidth = 480, targetHeight = 480)

        assertEquals(40 to 20, wide.width to wide.height)
        assertEquals(20 to 40, tall.width to tall.height)
        assertEquals(40 to 40, square.width to square.height)
        assertEquals(Color.RED, square[20, 20])
        assertEquals(192, artwork.width)
        assertTrue(!artwork.isRecycled)
    }

    @Test
    fun `small artwork is never upscaled for the blur source`() {
        val blur = playbackWidgetBlurSource(solidBitmap(16, 16, Color.BLUE), 160, 160)

        assertEquals(16 to 16, blur.width to blur.height)
    }

    @Test
    fun `compact backdrop keeps the surface size and renders a soft blur under the scrim`() {
        val stripes = createBitmap(192, 192).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    this[x, y] = if ((x / 4) % 2 == 0) Color.WHITE else Color.BLACK
                }
            }
        }
        val size = PlaybackWidgetSize(widthDp = 160, heightDp = 160)

        val plain = playbackWidgetBackdrop(stripes, size, cornerRadiusDp = 16f, renderScale = 3f)
        val scrimmed = playbackWidgetBackdrop(
            stripes,
            size,
            cornerRadiusDp = 16f,
            applyScrim = true,
            renderScale = 3f
        )

        assertEquals(480 to 480, plain.width to plain.height)
        val row = plain.height / 2
        var maxNeighborDelta = 0
        for (x in 40 until plain.width - 41) {
            maxNeighborDelta = maxOf(maxNeighborDelta, abs(Color.red(plain[x, row]) - Color.red(plain[x + 1, row])))
        }
        assertTrue("adjacent pixels jump by $maxNeighborDelta", maxNeighborDelta <= 12)
        assertTrue(Color.red(scrimmed[240, 240]) < Color.red(plain[240, 240]))
        assertEquals(0, Color.alpha(plain[0, 0]))
        assertEquals(255, Color.alpha(plain[240, 240]))
    }

    @Test
    fun `box blur pass averages neighbors along one axis and clamps at edges`() {
        val black = Color.BLACK
        val white = Color.WHITE
        val row = intArrayOf(white, black, black, black, white)

        val horizontal = boxBlurPass(row, width = 5, height = 1, radius = 1, horizontal = true)
        val vertical = boxBlurPass(row, width = 1, height = 5, radius = 1, horizontal = false)

        val twoThirds = 0xFF * 2 / 3
        val oneThird = 0xFF / 3
        assertEquals(Color.argb(255, twoThirds, twoThirds, twoThirds), horizontal[0])
        assertEquals(Color.argb(255, oneThird, oneThird, oneThird), horizontal[1])
        assertEquals(black, horizontal[2])
        assertEquals(horizontal.toList(), vertical.toList())
    }

    @Test
    fun `visuals without artwork fall back to the default seed palette`() {
        val light = buildPlaybackWidgetVisuals(artwork = null)
        val dark = buildPlaybackWidgetVisuals(artwork = null, isDarkTheme = true)

        assertNull(light.artwork)
        assertNull(light.compactArtwork)
        assertNull(light.primaryControl)
        assertEquals(light.textPrimary, light.controlTint)
        assertEquals(Color.rgb(12, 24, 18), light.primaryControlTint)
        assertTrue(luminance(light.backgroundColor) > luminance(dark.backgroundColor))
        assertTrue(luminance(dark.textPrimary) > luminance(light.textPrimary))
    }

    @Test
    fun `artwork is squared down to the widget artwork size and tints the palette`() {
        val wideRed = solidBitmap(400, 300, Color.rgb(200, 30, 30))

        val visuals = buildPlaybackWidgetVisuals(wideRed)

        val compact = visuals.compactArtwork
        assertNotNull(compact)
        assertEquals(192 to 192, compact!!.width to compact.height)
        assertEquals(192 to 192, visuals.artwork!!.width to visuals.artwork!!.height)
        assertEquals(96 to 96, visuals.primaryControl!!.width to visuals.primaryControl!!.height)
        assertEquals(0, Color.alpha(visuals.artwork!![0, 0]))
        val background = visuals.backgroundColor
        assertTrue(Color.red(background) > Color.green(background))
        assertNotEquals(buildPlaybackWidgetVisuals(null).backgroundColor, background)
    }

    @Test
    fun `seed sampling ignores transparent pixels and weighs saturated colors`() {
        val transparent = buildPlaybackWidgetVisuals(solidBitmap(64, 64, Color.TRANSPARENT))
        val mixed = createBitmap(96, 96).apply {
            for (x in 0 until width) {
                for (y in 0 until height) {
                    this[x, y] = when ((x + y) % 4) {
                        0 -> Color.BLACK
                        1 -> Color.WHITE
                        2 -> Color.TRANSPARENT
                        else -> Color.rgb(20, 160, 40)
                    }
                }
            }
        }

        val gray = buildPlaybackWidgetVisuals(solidBitmap(64, 64, Color.rgb(112, 112, 112)))
        val green = buildPlaybackWidgetVisuals(mixed)

        assertEquals(gray.backgroundColor, transparent.backgroundColor)
        assertEquals(gray.textPrimary, transparent.textPrimary)
        assertTrue(Color.green(green.backgroundColor) > Color.red(green.backgroundColor))
        assertTrue(Color.green(green.backgroundColor) > Color.blue(green.backgroundColor))
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        createBitmap(width, height).apply { eraseColor(color) }

    private fun luminance(color: Int): Int = Color.red(color) + Color.green(color) + Color.blue(color)
}
