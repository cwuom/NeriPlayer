package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

class PreferredRefreshRateWindowTest {

    private val attributes = WindowManager.LayoutParams()
    private val decorView = mock(View::class.java)
    private val window = mock(Window::class.java).also { window ->
        `when`(window.decorView).thenReturn(decorView)
        `when`(window.attributes).thenReturn(attributes)
    }
    private val activity = mock(Activity::class.java).also { `when`(it.window).thenReturn(window) }

    @Test
    fun `the fastest supported mode of the attached display is requested`() {
        val display = display(currentRate = 60f, 60f, 120f, 90f)
        `when`(decorView.display).thenReturn(display)

        activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = true)

        assertEquals(120f, attributes.preferredRefreshRate, 0f)
        verify(window).attributes = attributes
    }

    @Test
    fun `a detached window falls back to the default display`() {
        val displayManager = mock(DisplayManager::class.java)
        `when`(activity.getSystemService(Context.DISPLAY_SERVICE)).thenReturn(displayManager)
        val defaultDisplay = display(currentRate = 60f, 60f, 90f)
        `when`(displayManager.getDisplay(Display.DEFAULT_DISPLAY)).thenReturn(defaultDisplay)

        activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = true)

        assertEquals(90f, attributes.preferredRefreshRate, 0f)
    }

    @Test
    fun `a display without reported modes keeps its current refresh rate`() {
        val display = display(currentRate = 75f)
        `when`(decorView.display).thenReturn(display)

        activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = true)

        assertEquals(75f, attributes.preferredRefreshRate, 0f)
    }

    @Test
    fun `without any display an unchanged request is not reapplied`() {
        activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = true)

        assertEquals(0f, attributes.preferredRefreshRate, 0f)
        verify(window, never()).attributes = any()
    }

    @Test
    fun `disabling high refresh rate clears a previous request`() {
        attributes.preferredRefreshRate = 120f
        val display = display(currentRate = 60f, 60f, 120f)
        `when`(decorView.display).thenReturn(display)

        activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = false)

        assertEquals(0f, attributes.preferredRefreshRate, 0f)
        verify(window).attributes = attributes
    }

    @Test
    fun `an idle now playing screen drops to 60 Hz and restores the preference afterwards`() {
        val display = display(currentRate = 120f, 60f, 90f, 120f)
        `when`(decorView.display).thenReturn(display)

        val ambient = activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = true, ambientIdle = true)
        assertEquals(true, ambient)
        assertEquals(60f, attributes.preferredRefreshRate, 0f)

        val restored = activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = true, ambientIdle = false)
        assertEquals(false, restored)
        assertEquals(120f, attributes.preferredRefreshRate, 0f)
    }

    @Test
    fun `a 60 Hz display has nothing to save and keeps the normal preference`() {
        val display = display(currentRate = 60f, 60f)
        `when`(decorView.display).thenReturn(display)

        val ambient = activity.applyPreferredHighRefreshRate(highRefreshRateEnabled = false, ambientIdle = true)

        assertEquals(false, ambient)
        assertEquals(0f, attributes.preferredRefreshRate, 0f)
    }

    @Test
    fun `ambient idle needs a visible resumed player and three quiet seconds`() {
        assertEquals(true, isAmbientRefreshRateIdle(true, true, false, AMBIENT_REFRESH_RATE_IDLE_MS))
        assertEquals(false, isAmbientRefreshRateIdle(true, true, false, AMBIENT_REFRESH_RATE_IDLE_MS - 1))
        assertEquals(false, isAmbientRefreshRateIdle(true, true, true, AMBIENT_REFRESH_RATE_IDLE_MS))
        assertEquals(false, isAmbientRefreshRateIdle(true, false, false, AMBIENT_REFRESH_RATE_IDLE_MS))
        assertEquals(false, isAmbientRefreshRateIdle(false, true, false, AMBIENT_REFRESH_RATE_IDLE_MS))
        assertEquals(null, resolveAmbientRefreshRate(listOf(30f, 60f)))
        assertEquals(60f, resolveAmbientRefreshRate(listOf(30f, 60f, 90f, 120f)))
        assertEquals(90f, resolveAmbientRefreshRate(listOf(48f, 90f)))
    }

    private fun display(currentRate: Float, vararg supportedRates: Float): Display =
        mock(Display::class.java).also { display ->
            `when`(display.refreshRate).thenReturn(currentRate)
            if (supportedRates.isNotEmpty()) {
                val modes = supportedRates.map { rate ->
                    mock(Display.Mode::class.java).also { `when`(it.refreshRate).thenReturn(rate) }
                }.toTypedArray()
                `when`(display.supportedModes).thenReturn(modes)
            }
        }
}
