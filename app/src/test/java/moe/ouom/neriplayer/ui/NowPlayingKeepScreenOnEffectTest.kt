package moe.ouom.neriplayer.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 31, 35], application = Application::class)
class NowPlayingKeepScreenOnEffectTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val presentation = mutableStateOf(Presentation())
    private val mounted = mutableStateOf(true)
    private val configurationContext = mutableStateOf<Context?>(null)
    private var observedDensity = 0f

    @Test
    fun visibleResumedEnabledKeepsTheActivityWindowOnWithAConfigurationContext() {
        render()

        assertFlag(true)
    }

    @Test
    fun hidingTheOverlayReleasesAndReopeningReacquiresItsFlag() {
        render()
        assertFlag(true)

        composeRule.runOnIdle { presentation.value = presentation.value.copy(visible = false) }
        assertFlag(false)
        composeRule.runOnIdle { presentation.value = presentation.value.copy(visible = true) }
        assertFlag(true)
    }

    @Test
    fun pausingReleasesAndResumingReacquiresItsFlag() {
        render()
        assertFlag(true)

        composeRule.runOnIdle { presentation.value = presentation.value.copy(resumed = false) }
        assertFlag(false)
        composeRule.runOnIdle { presentation.value = presentation.value.copy(resumed = true) }
        assertFlag(true)
    }

    @Test
    fun disablingThePreferenceReleasesAndEnablingReacquiresItsFlag() {
        render()
        assertFlag(true)

        composeRule.runOnIdle { presentation.value = presentation.value.copy(enabled = false) }
        assertFlag(false)
        composeRule.runOnIdle { presentation.value = presentation.value.copy(enabled = true) }
        assertFlag(true)
    }

    @Test
    fun disposingTheEffectReleasesTheFlagItAdded() {
        render()
        assertFlag(true)

        composeRule.runOnIdle { mounted.value = false }

        assertFlag(false)
    }

    @Test
    fun hostOwnedFlagSurvivesInactiveStatesAndDisposal() {
        composeRule.runOnIdle {
            composeRule.activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        render()
        assertFlag(true)
        for (inactive in listOf(
            Presentation(visible = false), Presentation(resumed = false), Presentation(enabled = false)
        )) {
            composeRule.runOnIdle { presentation.value = inactive }
            assertFlag(true)
            composeRule.runOnIdle { presentation.value = Presentation() }
            assertFlag(true)
        }
        composeRule.runOnIdle { mounted.value = false }
        assertFlag(true)
    }

    @Test
    fun contextAndDensityReplacementKeepTheSameWindowOwnedUntilDisposal() {
        render()
        assertFlag(true)
        composeRule.runOnIdle { assertEquals(3.5f, observedDensity, 0f) }

        composeRule.runOnIdle {
            val previous = checkNotNull(configurationContext.value)
            configurationContext.value = createConfigurationContext(480)
            assertNotSame(previous, configurationContext.value)
        }

        assertFlag(true)
        composeRule.runOnIdle { assertEquals(3f, observedDensity, 0f) }
        composeRule.runOnIdle { mounted.value = false }
        assertFlag(false)
    }

    private fun render() {
        composeRule.runOnIdle { configurationContext.value = createConfigurationContext(560) }
        composeRule.setContent {
            val context = checkNotNull(configurationContext.value)
            CompositionLocalProvider(
                LocalActivity provides composeRule.activity,
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
                LocalDensity provides Density(context.resources.displayMetrics.density)
            ) {
                val density = LocalDensity.current.density
                SideEffect { observedDensity = density }
                if (mounted.value) {
                    val current = presentation.value
                    NowPlayingKeepScreenOnEffect(current.visible, current.resumed, current.enabled)
                }
            }
        }
    }

    private fun createConfigurationContext(densityDpi: Int): Context {
        val configuration = Configuration(composeRule.activity.resources.configuration).apply {
            this.densityDpi = densityDpi
        }
        return composeRule.activity.createConfigurationContext(configuration).also {
            assertFalse("resource configuration context must not be the Activity", it is Activity)
            assertEquals(densityDpi, it.resources.displayMetrics.densityDpi)
        }
    }

    private fun assertFlag(expected: Boolean) {
        composeRule.runOnIdle {
            val flag = WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            assertEquals("host Activity window keep-screen-on flag", expected,
                composeRule.activity.window.attributes.flags and flag == flag)
        }
    }

    private data class Presentation(
        val visible: Boolean = true,
        val resumed: Boolean = true,
        val enabled: Boolean = true
    )
}
