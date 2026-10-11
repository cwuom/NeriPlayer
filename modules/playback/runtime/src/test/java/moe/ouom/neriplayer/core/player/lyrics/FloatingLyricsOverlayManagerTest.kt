package moe.ouom.neriplayer.core.player.lyrics

import moe.ouom.neriplayer.core.player.lyrics.floating.FloatingLyricsContent
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_ALIGNMENT_LEFT
import moe.ouom.neriplayer.data.model.settings.lyrics.FLOATING_LYRICS_ALIGNMENT_RIGHT
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadow.api.Shadow
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowWindowManagerImpl
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class FloatingLyricsOverlayManagerTest {
    @Test
    @Config(sdk = [29, 36])
    fun `sentence count changes immediately and previews clear at song end`() {
        val preferences = FloatingLyricsPreferences(enabled = true, sentenceCount = 3, revealAnimationEnabled = false)
        FloatingLyricsOverlayManager.updatePreferences(preferences)
        FloatingLyricsOverlayManager.updateContent(
            FloatingLyricsContent(
                lyric = "current", translation = "译文", nextLyric = "next", secondNextLyric = "last"
            )
        )
        idle()
        val root = overlayRoot()
        assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE),
            (0 until root.childCount).map { root.getChildAt(it).visibility })
        FloatingLyricsOverlayManager.updatePreferences(preferences.copy(sentenceCount = 1))
        assertEquals(listOf(View.VISIBLE, View.VISIBLE, View.GONE, View.GONE),
            (0 until root.childCount).map { root.getChildAt(it).visibility })
        FloatingLyricsOverlayManager.updatePreferences(preferences.copy(sentenceCount = 2))
        assertEquals(View.VISIBLE, root.getChildAt(2).visibility)
        assertEquals(View.GONE, root.getChildAt(3).visibility)
        FloatingLyricsOverlayManager.updateContent(
            FloatingLyricsContent(lyric = "last")
        )
        idle()
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertEquals(View.GONE, root.getChildAt(2).visibility)
        FloatingLyricsOverlayManager.updateContent(null, null)
        idle()
        assertTrue(overlayViews().isEmpty())
    }

    @Test
    @Config(sdk = [29, 36])
    fun `requested width is bounded by the current screen`() {
        FloatingLyricsOverlayManager.updatePreferences(FloatingLyricsPreferences(
            enabled = true, maxWidthDp = 420f, landscapeMaxWidthDp = 1200f,
            longLineMode = "wrap", sentenceCount = 3
        ))
        FloatingLyricsOverlayManager.updateContent("current", null)
        idle()
        val screenWidth = app.resources.displayMetrics.widthPixels
        assertTrue(overlayParams().width in 1..screenWidth)
    }
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val windowShadow: ShadowWindowManagerImpl
        get() = Shadow.extract(app.getSystemService(Context.WINDOW_SERVICE))

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        FloatingLyricsOverlayManager.initialize(app)
    }

    @After
    fun tearDown() {
        FloatingLyricsOverlayManager.release()
        FloatingLyricsOverlayManager.updatePreferences(FloatingLyricsPreferences())
        FloatingLyricsOverlayManager.updateContent(null, null)
        idle()
    }

    @Test
    fun `overlay appears only once an enabled overlay has a lyric line`() {
        FloatingLyricsOverlayManager.updatePreferences(FloatingLyricsPreferences(enabled = true))
        assertTrue(overlayViews().isEmpty())

        FloatingLyricsOverlayManager.updateContent("   ", "translation")
        idle()
        assertTrue(overlayViews().isEmpty())

        FloatingLyricsOverlayManager.updateContent("  First line  ", null)
        FloatingLyricsOverlayManager.updateContent("Second line", "  ")
        idle()

        val root = overlayRoot()
        assertEquals(4, root.childCount)
        assertEquals(View.GONE, root.getChildAt(1).visibility)
        assertEquals(1, overlayViews().size)
    }

    @Test
    fun `translation preference applies from the next lyric line`() {
        showOverlay(FloatingLyricsPreferences(enabled = true), translation = "Translated")
        assertEquals(View.VISIBLE, overlayRoot().getChildAt(1).visibility)

        FloatingLyricsOverlayManager.updatePreferences(
            FloatingLyricsPreferences(
                enabled = true,
                showTranslation = false,
                revealAnimationEnabled = false,
                alignment = FLOATING_LYRICS_ALIGNMENT_LEFT
            )
        )
        idle()
        assertEquals(View.VISIBLE, overlayRoot().getChildAt(1).visibility)
        FloatingLyricsOverlayManager.updateContent("Next line", "Next translation")
        idle()
        assertEquals(View.GONE, overlayRoot().getChildAt(1).visibility)

        FloatingLyricsOverlayManager.updatePreferences(
            FloatingLyricsPreferences(enabled = true, alignment = FLOATING_LYRICS_ALIGNMENT_RIGHT, maxWidthDp = 260f)
        )
        FloatingLyricsOverlayManager.updateContent("Third line", "Third translation")
        idle()
        assertEquals(View.VISIBLE, overlayRoot().getChildAt(1).visibility)
    }

    @Test
    fun `lyric line pending while preferences change is still shown`() {
        FloatingLyricsOverlayManager.updateContent("Pending line", null)
        FloatingLyricsOverlayManager.updatePreferences(FloatingLyricsPreferences(enabled = true))
        idle()

        assertEquals(1, overlayViews().size)
    }

    @Test
    fun `overlay requires permission and an enabled preference`() {
        ShadowSettings.setCanDrawOverlays(false)
        showOverlay(FloatingLyricsPreferences(enabled = true))
        assertTrue(overlayViews().isEmpty())
        assertFalse(FloatingLyricsOverlayManager.hasOverlayPermission(app))

        ShadowSettings.setCanDrawOverlays(true)
        FloatingLyricsOverlayManager.updatePreferences(FloatingLyricsPreferences(enabled = true))
        assertEquals(1, overlayViews().size)

        FloatingLyricsOverlayManager.updatePreferences(FloatingLyricsPreferences(enabled = false))
        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `hide in app removes the overlay while an activity is started`() {
        showOverlay(FloatingLyricsPreferences(enabled = true, hideInApp = true))
        assertEquals(1, overlayViews().size)

        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(overlayViews().isEmpty())

        controller.pause().stop()
        idle()
        assertEquals(1, overlayViews().size)
        controller.destroy()
    }

    @Test
    fun `disabling long press drag makes the overlay untouchable`() {
        showOverlay(FloatingLyricsPreferences(enabled = true))
        assertEquals(0, overlayParams().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        assertTrue(overlayRoot().isLongClickable)

        FloatingLyricsOverlayManager.updatePreferences(
            FloatingLyricsPreferences(enabled = true, longPressDragEnabled = false)
        )
        idle()

        assertTrue(overlayParams().flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE != 0)
        assertFalse(overlayRoot().isLongClickable)
    }

    @Test
    fun `long press drag moves the overlay and reports the normalized position`() {
        val reported = mutableListOf<Triple<Float, Float, Boolean>>()
        FloatingLyricsOverlayManager.setPositionChangeListener { x, y, landscape ->
            reported += Triple(x, y, landscape)
        }
        showOverlay(FloatingLyricsPreferences(enabled = true, maxWidthDp = 120f, positionX = 0f, positionY = 0.5f))
        val root = overlayRoot()
        val startY = overlayParams().y

        root.dispatchTouchEvent(motion(MotionEvent.ACTION_DOWN, 10f, 10f))
        shadowOf(Looper.getMainLooper()).idleFor(
            Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 20L)
        )
        root.dispatchTouchEvent(motion(MotionEvent.ACTION_MOVE, 50f, 40f))
        root.dispatchTouchEvent(motion(MotionEvent.ACTION_UP, 50f, 40f))
        idle()

        assertEquals(40, overlayParams().x)
        assertEquals(startY + 30, overlayParams().y)
        val position = reported.single()
        assertTrue(position.first in 0f..1f && position.first > 0f)
        assertTrue(position.second in 0f..1f)
        assertFalse(position.third)
    }

    @Test
    fun `configuration changes keep the overlay inside the screen`() {
        showOverlay(FloatingLyricsPreferences(enabled = true, positionX = 1f, positionY = 1f))

        app.onConfigurationChanged(Configuration(app.resources.configuration))
        idle()

        val params = overlayParams()
        val screen = app.resources.displayMetrics
        assertTrue(params.x in 0..screen.widthPixels)
        assertTrue(params.y <= screen.heightPixels)
    }

    @Test
    fun `release removes the overlay and stops reacting to updates`() {
        showOverlay(FloatingLyricsPreferences(enabled = true))
        FloatingLyricsOverlayManager.updatePlaybackState(true)
        FloatingLyricsOverlayManager.updatePlaybackState(true)
        idle()
        assertEquals(1, overlayViews().size)

        FloatingLyricsOverlayManager.release()
        FloatingLyricsOverlayManager.release()
        FloatingLyricsOverlayManager.updateContent("Another line", null)
        idle()

        assertTrue(overlayViews().isEmpty())
        app.onConfigurationChanged(Configuration(app.resources.configuration))
        idle()
        assertTrue(overlayViews().isEmpty())
    }

    @Test
    fun `overlay text resolution keeps translations only when requested and present`() {
        val shown = resolveFloatingLyricsOverlayText(FloatingLyricsPreferences(), "Line", "Translated")
        assertTrue(shown.showTranslation)
        assertEquals("Translated", shown.translation)
        assertEquals(
            AnimatedOutlinedLyricTextView.resolveRevealDurationMs("Translated"),
            shown.revealDurationMs
        )

        val blank = resolveFloatingLyricsOverlayText(FloatingLyricsPreferences(), null, " ")
        assertFalse(blank.showTranslation)
        assertEquals("", blank.lyric)
        assertEquals("", blank.translation)

        val hidden = resolveFloatingLyricsOverlayText(
            FloatingLyricsPreferences(showTranslation = false, revealAnimationEnabled = false),
            "Line",
            "Translated"
        )
        assertFalse(hidden.showTranslation)
        assertNull(hidden.revealDurationMs)
        assertFalse(hidden.revealAnimationEnabled)
    }

    private fun showOverlay(preferences: FloatingLyricsPreferences, translation: String? = null) {
        FloatingLyricsOverlayManager.updatePreferences(preferences)
        FloatingLyricsOverlayManager.updateContent("Line", translation)
        idle()
    }

    private fun overlayViews(): List<LinearLayout> = windowShadow.views.filterIsInstance<LinearLayout>()

    private fun overlayRoot(): LinearLayout = overlayViews().single()

    private fun overlayParams(): WindowManager.LayoutParams =
        overlayRoot().layoutParams as WindowManager.LayoutParams

    private fun motion(action: Int, x: Float, y: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        return MotionEvent.obtain(now, now, action, x, y, 0)
    }

    private fun idle() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }
}
