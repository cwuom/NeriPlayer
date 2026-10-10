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
