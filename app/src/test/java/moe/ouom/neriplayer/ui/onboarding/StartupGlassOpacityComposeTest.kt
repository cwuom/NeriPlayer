package moe.ouom.neriplayer.ui.onboarding

import android.app.Application
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalSharedTransitionApi::class)
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp")
class StartupGlassOpacityComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val themeInputs = StartupThemeInputs(
        isDark = false,
        dynamicColor = false,
        seedColorHex = "4A5A8A",
        paletteStyle = PaletteStyle.TonalSpot,
        colorSpec = ColorSpec.SpecVersion.Default
    )

    @Test
    fun `glass layer fades together with the mini player`() {
        var visible by mutableStateOf(true)
        val samples = mutableListOf<Float>()
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            AnimatedVisibility(visible = visible, exit = fadeOut(tween(120))) {
                val opacity = rememberStartupGlassOpacity(enterMillis = 180, exitMillis = 120)
                samples += opacity()
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.advanceTimeBy(400L)
        val before = samples.last()
        visible = false
        composeRule.waitForIdle()
        repeat(4) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
        assertEquals("显示时玻璃层完全不透明", 1f, before, 0.001f)
        assertTrue("退场过程中玻璃层要跟着变淡: $samples", samples.last() < 1f)
    }
}
