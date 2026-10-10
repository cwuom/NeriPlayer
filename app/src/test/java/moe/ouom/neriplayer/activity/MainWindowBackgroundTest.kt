package moe.ouom.neriplayer.activity

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric

@RunWith(AndroidJUnit4::class)
class MainWindowBackgroundTest {

    @Test
    @Suppress("DEPRECATION")
    fun `light theme keeps both system bars transparent without contrast scrims`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.enableEdgeToEdge()
        assertTrue(
            "edge-to-edge enables the navigation bar contrast scrim by default",
            activity.window.isNavigationBarContrastEnforced
        )

        activity.window.applyMainWindowBackground(isDark = false)

        val window = activity.window
        assertFalse(window.isNavigationBarContrastEnforced)
        assertFalse(window.isStatusBarContrastEnforced)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertEquals(Color.WHITE, (window.decorView.background as ColorDrawable).color)
    }

    @Test
    fun `dark theme uses the dark window background`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

        activity.window.applyMainWindowBackground(isDark = true)

        assertEquals(
            Color.rgb(0x12, 0x12, 0x12),
            (activity.window.decorView.background as ColorDrawable).color
        )
        assertFalse(activity.window.isNavigationBarContrastEnforced)
    }
}
