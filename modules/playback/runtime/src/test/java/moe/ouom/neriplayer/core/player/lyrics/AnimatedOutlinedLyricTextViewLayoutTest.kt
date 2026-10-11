package moe.ouom.neriplayer.core.player.lyrics

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_RENDER_STYLE_OUTLINE
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [29, 36])
class AnimatedOutlinedLyricTextViewLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `wrapping caches layout across draws and remeasures on width or text changes`() {
        val view = newView()
        view.setWrapLongLines(true)
        measure(view, 180)
        val narrowHeight = view.measuredHeight
        val layout = cachedLayout(view)
        assertTrue(layout != null)
        val bitmap = Bitmap.createBitmap(view.measuredWidth, view.measuredHeight, Bitmap.Config.ARGB_8888)
        repeat(10) { view.draw(Canvas(bitmap)) }
        assertSame(layout, cachedLayout(view))
        bitmap.recycle()
        measure(view, 180)
        assertSame(layout, cachedLayout(view))
        measure(view, 1000)
        assertTrue(view.measuredHeight < narrowHeight)
        view.setLyricText("one", revealAnimationEnabled = false)
        assertNull(cachedLayout(view))
        measure(view, 180)
        assertTrue(view.measuredHeight < narrowHeight)
    }

    @Test
    fun `switching back to scrolling restores a single line height`() {
        val view = newView()
        view.setWrapLongLines(true)
        measure(view, 180)
        val wrappedHeight = view.measuredHeight
        view.setWrapLongLines(false)
        measure(view, 180)
        assertTrue(view.measuredHeight < wrappedHeight)
        assertNull(cachedLayout(view))
    }

    private fun newView() = AnimatedOutlinedLyricTextView(context).apply {
        setLyricStyle(android.graphics.Color.WHITE, android.graphics.Color.BLACK, 28f, 2f,
            FLOATING_LYRICS_RENDER_STYLE_OUTLINE, true)
        setLyricText("A long lyric sentence with words that need wrapping across several lines", revealAnimationEnabled = false)
    }

    private fun measure(view: View, width: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun cachedLayout(view: AnimatedOutlinedLyricTextView): Any? =
        AnimatedOutlinedLyricTextView::class.java.getDeclaredField("wrappedLayout")
            .apply { isAccessible = true }.get(view)
}
