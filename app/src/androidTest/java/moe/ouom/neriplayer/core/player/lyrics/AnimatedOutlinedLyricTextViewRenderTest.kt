package moe.ouom.neriplayer.core.player.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.settings.FLOATING_LYRICS_RENDER_STYLE_SHADOW
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnimatedOutlinedLyricTextViewRenderTest {

    @Test
    fun overflowingLyricKeepsFillOpaqueAtViewportEdge() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = createView(context)
        invokeSetLyricStyle(view)
        invokeSetLyricText(view)
        invokeSetPlaybackActive(view)

        val bitmap = render(view)
        try {
            val edgeStart = view.paddingLeft + 2
            val edgeEnd = (view.paddingLeft + 22).coerceAtMost(bitmap.width)
            val hasOpaqueEdgeGlyph = (edgeStart until edgeEnd).any { x ->
                (0 until bitmap.height).any { y ->
                    val pixel = bitmap.getPixel(x, y)
                    Color.alpha(pixel) >= 250 &&
                        Color.red(pixel) >= 240 &&
                        Color.green(pixel) >= 240 &&
                        Color.blue(pixel) >= 240
                }
            }
            assertTrue("edge lyric fill should remain opaque", hasOpaqueEdgeGlyph)
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun partialOpacityIsAppliedOnceAtViewportCenter() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = createView(context)
        invokeSetLyricStyle(view, textColor = 0x80FFFFFF.toInt())
        invokeSetLyricText(view)
        invokeSetPlaybackActive(view)

        val bitmap = render(view)
        try {
            var maximumWhiteAlpha = 0
            for (x in bitmap.width / 3 until bitmap.width * 2 / 3) {
                for (y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) >= 240 &&
                        Color.green(pixel) >= 240 &&
                        Color.blue(pixel) >= 240
                    ) {
                        maximumWhiteAlpha = maxOf(maximumWhiteAlpha, Color.alpha(pixel))
                    }
                }
            }
            assertTrue(
                "partial opacity should be composited once: $maximumWhiteAlpha",
                maximumWhiteAlpha in 120..136
            )
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun partialOpacityKeepsStaticLyricFillBright() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val view = createView(context)
        invokeSetLyricStyle(view, textColor = 0x80FFFFFF.toInt())
        invokeSetLyricText(view, text = "WW")
        invokeSetPlaybackActive(view)

        val bitmap = render(view)
        try {
            var maximumWhiteAlpha = 0
            for (x in bitmap.width / 3 until bitmap.width * 2 / 3) {
                for (y in 0 until bitmap.height) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) >= 240 &&
                        Color.green(pixel) >= 240 &&
                        Color.blue(pixel) >= 240
                    ) {
                        maximumWhiteAlpha = maxOf(maximumWhiteAlpha, Color.alpha(pixel))
                    }
                }
            }
            assertTrue(
                "static lyric fill should not be darkened by its shadow: $maximumWhiteAlpha",
                maximumWhiteAlpha in 120..136
            )
        } finally {
            bitmap.recycle()
        }
    }

    private fun createView(context: Context): View {
        val viewClass = Class.forName(
            "moe.ouom.neriplayer.core.player.lyrics.AnimatedOutlinedLyricTextView"
        )
        return viewClass.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }
            .newInstance(context) as View
    }

    private fun invokeSetLyricStyle(view: View, textColor: Int = Color.WHITE) {
        val intType = Int::class.javaPrimitiveType!!
        val floatType = Float::class.javaPrimitiveType!!
        val booleanType = Boolean::class.javaPrimitiveType!!
        view.javaClass.getMethod(
            "setLyricStyle",
            intType,
            intType,
            floatType,
            floatType,
            String::class.java,
            booleanType
        ).invoke(view, textColor, Color.BLACK, 48f, 4f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, true)
    }

    private fun invokeSetLyricText(view: View, text: String = "WWWWWWWWWWWWWWWWWWWW") {
        view.javaClass.getMethod(
            "setLyricText",
            String::class.java,
            Long::class.javaObjectType,
            Boolean::class.javaPrimitiveType
        ).invoke(view, text, null, false)
    }

    private fun invokeSetPlaybackActive(view: View) {
        view.javaClass.getMethod(
            "setPlaybackActive",
            Boolean::class.javaPrimitiveType
        ).invoke(view, false)
    }

    private fun render(view: View): Bitmap {
        val width = 240
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        view.layout(0, 0, width, view.measuredHeight)
        return Bitmap.createBitmap(width, view.measuredHeight, Bitmap.Config.ARGB_8888).also { bitmap ->
            Canvas(bitmap).apply {
                drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                view.draw(this)
            }
        }
    }
}
