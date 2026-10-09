package moe.ouom.neriplayer.ui.component.lyrics

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LyricShareCardBlurTest {

    private val source = intArrayOf(
        Color.argb(255, 10, 20, 30),
        Color.argb(255, 200, 100, 0),
        Color.argb(128, 0, 0, 250),
        Color.argb(255, 90, 60, 30),
        Color.argb(64, 255, 255, 255),
        Color.argb(255, 0, 120, 60)
    )

    private fun blurred(radius: Int): List<List<Int>> {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(source, 0, 3, 0, 0, 3, 2)
        bitmap.boxBlurOnce(radius)
        val pixels = IntArray(6)
        bitmap.getPixels(pixels, 0, 3, 0, 0, 3, 2)
        return pixels.map { color ->
            listOf(Color.alpha(color), Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    @Test
    fun `box blur averages clamped neighbours horizontally then vertically`() {
        assertEquals(
            listOf(
                listOf(233, 97, 72, 48),
                listOf(205, 85, 75, 100),
                listOf(177, 72, 77, 152),
                listOf(212, 121, 98, 76),
                listOf(198, 100, 110, 107),
                listOf(184, 78, 121, 138)
            ),
            blurred(radius = 1)
        )
    }

    @Test
    fun `box blur radius wider than the bitmap repeats edge pixels`() {
        assertEquals(
            listOf(
                listOf(223, 69, 63, 73),
                listOf(208, 61, 66, 102),
                listOf(193, 52, 68, 130),
                listOf(221, 81, 79, 75),
                listOf(211, 69, 85, 97),
                listOf(200, 58, 90, 118)
            ),
            blurred(radius = 2)
        )
    }
}
