package moe.ouom.neriplayer.core.player.service.lyrics

import android.app.Application
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LiveUpdateVisualsTest {
    @Test
    fun `dull cover colors are lifted to a vivid mid tone with the same hue`() {
        val dull = 0xFF706098.toInt()

        val lifted = hsl(checkNotNull(resolveLiveUpdateAccentColor(dull)))

        assertEquals(hsl(dull)[0], lifted[0], 1f)
        assertTrue("saturation ${lifted[1]}", lifted[1] >= 0.54f)
        assertTrue("lightness ${lifted[2]}", lifted[2] in 0.47f..0.63f)
    }

    @Test
    fun `very dark and very pale covers are pulled into a readable lightness`() {
        val navy = hsl(checkNotNull(resolveLiveUpdateAccentColor(0xFF0A1A40.toInt())))
        val blush = hsl(checkNotNull(resolveLiveUpdateAccentColor(0xFFF0D8E0.toInt())))

        assertTrue("navy lightness ${navy[2]}", navy[2] >= 0.47f)
        assertTrue("blush lightness ${blush[2]}", blush[2] <= 0.63f)
    }

    @Test
    fun `colors that are already vivid are kept exactly`() {
        assertEquals(0xFF3482FF.toInt(), resolveLiveUpdateAccentColor(0xFF3482FF.toInt()))
    }

    @Test
    fun `bright hues are toned down until they stand out on light and dark cards`() {
        listOf(0xFFFFFF00.toInt(), 0xFF00FFFF.toInt(), 0xFFB0FF20.toInt(), 0xFF0000FF.toInt()).forEach { cover ->
            val accent = checkNotNull(resolveLiveUpdateAccentColor(cover))

            assertTrue("light ${Integer.toHexString(cover)}", ColorUtils.calculateContrast(accent, Color.WHITE) >= 1.3)
            assertTrue("dark ${Integer.toHexString(cover)}", ColorUtils.calculateContrast(accent, 0xFF1F1F1F.toInt()) >= 1.3)
            assertEquals(hsl(cover)[0], hsl(accent)[0], 1.5f)
        }
    }

    @Test
    fun `accent is loaded once for the cover of the song on screen`() {
        assertTrue(load())
        assertFalse(load(enabled = false))
        assertFalse(load(frameSongKey = "netease:2"))
        assertFalse(load(frameSongKey = null))
        assertFalse(load(hasAccent = true))
        assertFalse(load(loading = true))
        // 同一封面取色失败过就不再逐帧重试；被取消的取色不算尝试过，会重新发起
        assertFalse(load(alreadyAttempted = true))
    }

    private fun load(
        enabled: Boolean = true,
        frameSongKey: String? = "netease:1",
        hasAccent: Boolean = false,
        loading: Boolean = false,
        alreadyAttempted: Boolean = false,
    ) = shouldLoadLiveUpdateAccent(enabled, "netease:1", frameSongKey, hasAccent, loading, alreadyAttempted)

    @Test
    fun `gray covers and missing colors defer to the system accent`() {
        assertNull(resolveLiveUpdateAccentColor(0xFF808080.toInt()))
        assertNull(resolveLiveUpdateAccentColor(0xFF2A2B2C.toInt()))
        assertNull(resolveLiveUpdateAccentColor(null))
    }

    @Test
    fun `tracker is an accent dot with a white ring and a fading glow`() {
        val accent = 0xFF6437BE.toInt()
        val size = LIVE_UPDATE_TRACKER_SIZE_PX
        val center = size / 2

        val tracker = createLiveUpdateTrackerBitmap(accent)

        assertEquals(size, tracker.width)
        assertEquals(size, tracker.height)
        assertEquals(accent, tracker.getPixel(center, center))
        assertEquals(Color.WHITE, tracker.getPixel(center + (size * 0.23f).toInt(), center))
        val glow = tracker.getPixel(center + (size * 0.4f).toInt(), center)
        assertTrue("glow alpha ${Color.alpha(glow)}", Color.alpha(glow) in 1..0xFE)
        assertEquals(0, Color.alpha(tracker.getPixel(0, 0)))
    }

    private fun hsl(color: Int): FloatArray = FloatArray(3).also { ColorUtils.colorToHSL(color, it) }
}
