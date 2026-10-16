package moe.ouom.neriplayer.activity

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

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

    @Test
    @Config(sdk = [35])
    fun `caption stays transparent across light dark light while preserving status and navigation appearance`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val window = activity.window
        val controller = checkNotNull(window.insetsController)
        val existingMask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, existingMask)

        for (useLightIcons in listOf(false, true, false)) {
            window.applyMainWindowCaptionAppearance(useLightIcons)

            val appearance = controller.systemBarsAppearance
            assertTrue(appearance and WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND != 0)
            assertEquals(!useLightIcons, appearance and WindowInsetsController.APPEARANCE_LIGHT_CAPTION_BARS != 0)
            assertEquals(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS, appearance and existingMask)
        }
    }

    @Test
    @Config(sdk = [28, 34])
    fun `caption appearance is inert before API 35 and preserves the window background and flags`() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val window = activity.window
        val background = ColorDrawable(Color.MAGENTA)
        window.setBackgroundDrawable(background)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val flags = window.attributes.flags
        val controller = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            checkNotNull(window.insetsController).also {
                it.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                        WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
            }
        } else null
        val appearance = controller?.systemBarsAppearance

        window.applyMainWindowCaptionAppearance(useLightSystemBarIcons = false)
        window.applyMainWindowCaptionAppearance(useLightSystemBarIcons = true)

        assertSame(background, window.decorView.background)
        assertEquals(flags, window.attributes.flags)
        assertEquals(appearance, controller?.systemBarsAppearance)
    }
}
