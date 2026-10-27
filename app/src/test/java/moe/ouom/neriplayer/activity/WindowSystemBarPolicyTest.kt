package moe.ouom.neriplayer.activity

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class WindowSystemBarPolicyTest {
    @Test
    @Config(sdk = [29, 35])
    fun configurationChangesKeepSystemBarContrastScrimsDisabled() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.enableNeriEdgeToEdge()
        val window = activity.window
        assertFalse("启动时导航栏必须无对比度遮罩", window.isNavigationBarContrastEnforced)

        for (nightMode in listOf(Configuration.UI_MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_NO)) {
            val nextConfiguration = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
            }
            window.decorView.dispatchConfigurationChanged(nextConfiguration)

            assertFalse("配置分发后不能重新启用导航栏对比度遮罩", window.isNavigationBarContrastEnforced)
            assertFalse("配置分发后不能启用状态栏对比度遮罩", window.isStatusBarContrastEnforced)
        }
    }

    @Test
    @Config(sdk = [28, 29, 35], qualifiers = "notnight")
    fun lightActivityKeepsDarkIconsAndItsExistingBackground() {
        assertActivityAppearance(isDark = false)
    }

    @Test
    @Config(sdk = [28, 29, 35], qualifiers = "night")
    fun darkActivityKeepsLightIconsAndItsExistingBackground() {
        assertActivityAppearance(isDark = true)
    }

    @Test
    @Config(sdk = [28, 29, 35])
    @Suppress("DEPRECATION")
    fun overlayTransparencyPreservesLayoutFlagsBackgroundAndIconAppearance() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val window = checkNotNull(Dialog(activity).window)
        val background = ColorDrawable(Color.MAGENTA)
        window.setBackgroundDrawable(background)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = false
        window.statusBarColor = Color.BLUE
        window.navigationBarColor = Color.RED
        window.navigationBarDividerColor = Color.GREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = true
            window.isNavigationBarContrastEnforced = true
        }
        val flags = window.attributes.flags
        val softInputMode = window.attributes.softInputMode
        val cutoutMode = window.attributes.layoutInDisplayCutoutMode
        val systemUiVisibility = window.decorView.systemUiVisibility

        window.applyNeriSystemBarTransparency()

        assertTransparentSystemBars(window)
        assertSame(background, window.decorView.background)
        assertEquals(flags, window.attributes.flags)
        assertEquals(softInputMode, window.attributes.softInputMode)
        assertEquals(cutoutMode, window.attributes.layoutInDisplayCutoutMode)
        assertEquals(systemUiVisibility, window.decorView.systemUiVisibility)
        assertEquals(true, controller.isAppearanceLightStatusBars)
        assertEquals(false, controller.isAppearanceLightNavigationBars)
    }

    private fun assertActivityAppearance(isDark: Boolean) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val background = ColorDrawable(Color.MAGENTA)
        activity.window.setBackgroundDrawable(background)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        activity.enableNeriEdgeToEdge()

        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertTransparentSystemBars(window)
        assertSame(background, window.decorView.background)
        assertEquals(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        assertEquals(!isDark, controller.isAppearanceLightStatusBars)
        assertEquals(!isDark, controller.isAppearanceLightNavigationBars)
    }

    @Suppress("DEPRECATION")
    private fun assertTransparentSystemBars(window: Window) {
        assertEquals(Color.TRANSPARENT, window.statusBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarDividerColor)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertFalse(window.isStatusBarContrastEnforced)
            assertFalse(window.isNavigationBarContrastEnforced)
        }
    }
}
