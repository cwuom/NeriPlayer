package moe.ouom.neriplayer.core.player.lyrics

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_RENDER_STYLE_OUTLINE
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_RENDER_STYLE_SHADOW
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

@RunWith(AndroidJUnit4::class)
class AnimatedOutlinedLyricTextViewRenderingTest {
    private lateinit var activity: Activity
    private lateinit var container: FrameLayout

    @Before
    fun setUp() {
        activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        container = FrameLayout(activity)
        activity.setContentView(container)
    }

    @Test
    fun `outline and shadow styles size the paddings for their effect`() {
        val view = attachedView(width = 400)
        val onePx = (activity.resources.displayMetrics.density).let { Math.round(it) }

        view.setLyricStyle(WHITE, BLACK, 32f, 4f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = true)
        assertEquals(4 + onePx, view.paddingLeft)
        assertEquals(2, view.paddingTop)
        assertEquals(View.LAYER_TYPE_NONE, view.layerType)

        view.setLyricStyle(WHITE, BLACK, 32f, 4f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        assertEquals(4 + onePx, view.paddingLeft)
        assertEquals(4 + 2 + onePx, view.paddingTop)
        assertEquals(View.LAYER_TYPE_SOFTWARE, view.layerType)

        view.setLyricStyle(WHITE, BLACK, 32f, -3f, "unknown", bold = false)
        assertEquals(onePx, view.paddingLeft)
        assertEquals(View.LAYER_TYPE_NONE, view.layerType)
    }

    @Test
    fun `reapplying the same style does not request a new layout`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 4f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = false)
        idle()
        assertFalse(view.isLayoutRequested)

        view.setLyricStyle(WHITE, BLACK, 32f, 4f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = false)
        assertFalse(view.isLayoutRequested)

        view.setLyricStyle(WHITE, BLACK, 32f, 6f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = false)
        assertTrue(view.isLayoutRequested)
    }

    @Test
    fun `measured width follows the lyric text`() {
        val view = AnimatedOutlinedLyricTextView(activity)
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)

        view.measure(unspecified, unspecified)
        val emptyWidth = view.measuredWidth
        view.setLyricText("A much longer lyric line", revealAnimationEnabled = false)
        view.measure(unspecified, unspecified)
        view.setLyricStyle(WHITE, BLACK, 32f, 4f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = false)

        assertTrue(view.measuredWidth > emptyWidth)
        assertTrue(view.preferredMeasuredHeightPx() >= 4 + view.paddingTop + view.paddingBottom)
    }

    @Test
    fun `revealed text stays hidden until the reveal animation runs`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 0f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        assertTrue(draw(view).texts.isEmpty())

        view.setLyricText("Hello", revealDurationMs = 400L)
        assertTrue(draw(view).texts.isEmpty())

        idle()
        val revealed = draw(view)
        assertEquals(listOf("Hello"), revealed.texts.map { it.text })
        assertEquals(view.width.toFloat(), revealed.lastClipRight)

        view.setLyricText("Hello", revealDurationMs = 400L)
        assertEquals(listOf("Hello"), draw(view).texts.map { it.text })
    }

    @Test
    fun `disabling reveal shows the full line immediately`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 0f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        view.setLyricText("Line", revealDurationMs = 800L)
        view.setRevealAnimationEnabled(true)
        assertTrue(draw(view).texts.isEmpty())

        view.setRevealAnimationEnabled(false)
        assertEquals(view.width.toFloat(), draw(view).lastClipRight)
        view.setRevealAnimationEnabled(false)

        view.setLyricText("", revealDurationMs = null)
        assertTrue(draw(view).texts.isEmpty())
        view.setLyricText("Next")
        idle()
        assertEquals(listOf("Next"), draw(view).texts.map { it.text })
    }

    @Test
    fun `outline style draws the stroke inside an erasing layer`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 3f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = false)
        view.setLyricText("Outline", revealAnimationEnabled = false)

        val frame = draw(view)

        assertEquals(1, frame.layers)
        assertEquals(
            listOf(Paint.Style.FILL, Paint.Style.STROKE, Paint.Style.FILL),
            frame.texts.map { it.style }
        )
        assertEquals(listOf(false, false, true), frame.texts.map { it.erases })
    }

    @Test
    fun `shadow style draws the shadowed fill inside an erasing layer`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 3f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        view.setLyricText("Shadow", revealAnimationEnabled = false)

        val frame = draw(view)

        assertEquals(1, frame.layers)
        assertEquals(3, frame.texts.size)
        assertEquals(0f, frame.lastClipLeft)
    }

    @Test
    fun `plain style draws a single unlayered line`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 0f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        view.setLyricText("Plain", revealAnimationEnabled = false)

        val frame = draw(view)

        assertEquals(0, frame.layers)
        assertEquals(listOf("Plain"), frame.texts.map { it.text })
    }

    @Test
    fun `alignment animates short lines towards the requested edge`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 0f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        view.setLyricText("Hi", revealAnimationEnabled = false)
        val centeredX = draw(view).texts.single().x

        view.setAlignmentFactor(1f)
        view.setAlignmentFactor(-1f)
        idle()
        val startX = draw(view).texts.single().x
        view.setAlignmentFactor(0f)
        view.setAlignmentFactor(1f)
        idle()
        val endX = draw(view).texts.single().x

        assertEquals(view.paddingLeft.toFloat(), startX, 0.5f)
        assertEquals(centeredX - startX, endX - centeredX, 0.5f)
    }

    @Test
    fun `long lines start from the leading edge and keep rendering across playback and screen changes`() {
        val view = attachedView(width = 80)
        view.setLyricStyle(WHITE, BLACK, 32f, 2f, FLOATING_LYRICS_RENDER_STYLE_OUTLINE, bold = false)
        view.setLyricText(LONG_LINE, revealAnimationEnabled = false)
        view.setPlaybackActive(true)
        view.setLyricText("$LONG_LINE!", revealAnimationEnabled = false)
        idle()
        val contentLeft = view.paddingLeft + 2f

        view.setPlaybackActive(true)
        view.setPlaybackActive(false)
        view.onScreenStateChanged(View.SCREEN_STATE_ON)
        view.setPlaybackActive(true)
        view.onScreenStateChanged(View.SCREEN_STATE_OFF)
        view.setPlaybackActive(false)
        view.setPlaybackActive(true)
        view.onScreenStateChanged(View.SCREEN_STATE_ON)
        view.onScreenStateChanged(View.SCREEN_STATE_ON)
        view.refreshScrollAfterLayout()
        view.onScreenStateChanged(View.SCREEN_STATE_ON)
        idle()

        val frame = draw(view)
        assertEquals("$LONG_LINE!", frame.texts.first().text)
        assertTrue(frame.texts.all { it.x <= contentLeft })
    }

    @Test
    fun `detaching cancels a pending reveal`() {
        val view = attachedView(width = 400)
        view.setLyricStyle(WHITE, BLACK, 32f, 0f, FLOATING_LYRICS_RENDER_STYLE_SHADOW, bold = false)
        view.setAlignmentFactor(0f)
        view.setLyricText("Detached", revealDurationMs = 800L)

        container.removeView(view)
        idle()

        assertTrue(draw(view).texts.isEmpty())
        val untouched = AnimatedOutlinedLyricTextView(activity)
        container.addView(untouched)
        container.removeView(untouched)
        assertTrue(draw(untouched).texts.isEmpty())
    }

    @Test
    fun `reveal window covers the full bounds once the reveal is complete`() {
        assertEquals(
            AnimatedOutlinedLyricTextView.LyricRevealWindow(0f, 100f),
            AnimatedOutlinedLyricTextView.resolveRevealWindow(1f, 10f, 50f, 0f, 100f, 2f)
        )
        assertEquals(
            AnimatedOutlinedLyricTextView.LyricRevealWindow(8f, 37f),
            AnimatedOutlinedLyricTextView.resolveRevealWindow(0.5f, 10f, 50f, 0f, 100f, 2f)
        )
        assertNull(AnimatedOutlinedLyricTextView.resolveRevealWindow(0.5f, 120f, 50f, 0f, 100f, 2f))
    }

    private fun attachedView(width: Int): AnimatedOutlinedLyricTextView {
        val view = AnimatedOutlinedLyricTextView(activity)
        container.addView(view, FrameLayout.LayoutParams(width, 60))
        idle()
        return view
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun exactly(size: Int): Int = View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY)

    private fun draw(view: View): RecordingCanvas {
        if (view.isLayoutRequested && view.width > 0) {
            view.measure(exactly(view.width), exactly(view.height))
            view.layout(view.left, view.top, view.right, view.bottom)
        }
        val canvas = RecordingCanvas(
            Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        )
        view.draw(canvas)
        return canvas
    }

    private data class DrawnText(val text: String, val x: Float, val style: Paint.Style, val erases: Boolean)

    private class RecordingCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        val texts = mutableListOf<DrawnText>()
        var layers = 0
        var rects = 0
        var lastClipLeft = Float.NaN
        var lastClipRight = Float.NaN

        override fun drawText(text: String, x: Float, y: Float, paint: Paint) {
            texts += DrawnText(text, x, paint.style, paint.xfermode != null)
        }

        override fun saveLayer(left: Float, top: Float, right: Float, bottom: Float, paint: Paint?): Int {
            layers += 1
            return save()
        }

        override fun clipRect(left: Float, top: Float, right: Float, bottom: Float): Boolean {
            lastClipLeft = left
            lastClipRight = right
            return super.clipRect(left, top, right, bottom)
        }

        override fun drawRect(left: Float, top: Float, right: Float, bottom: Float, paint: Paint) {
            rects += 1
        }
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        val LONG_LINE = "Lyric line that is far wider than the overlay ".repeat(4)
    }
}
