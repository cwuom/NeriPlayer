package moe.ouom.neriplayer.activity

import android.app.Application
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsKeys
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUiDensityRootTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var childWidthPx = 0
    private var appliedDensity = 0f
    private var appliedFontScale = 0f
    private val smallestWidth = mutableIntStateOf(800)
    private val deviceUiMode = mutableIntStateOf(Configuration.UI_MODE_TYPE_NORMAL)
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val originalDensity = application.resources.displayMetrics.density
    private val originalDensityDpi = application.resources.displayMetrics.densityDpi

    @Test
    fun tabletTwoTimesScaleChangesOnlyTheAppDensityAndKeepsFontScale() {
        render(smallestWidth = 600, uiMode = Configuration.UI_MODE_TYPE_NORMAL, requestedScale = 2f)
        assertScale(2f, 200)
    }

    @Test
    fun restoredTabletPreferenceCannotEnlargeAPhoneAboveItsOriginalLimit() {
        render(smallestWidth = 599, uiMode = Configuration.UI_MODE_TYPE_NORMAL, requestedScale = 2f)
        assertScale(1.2f, 120)
    }

    @Test
    fun carModeAtPhoneWidthCanApplyTwoTimesWithoutChangingSystemMetrics() {
        render(smallestWidth = 320, uiMode = Configuration.UI_MODE_TYPE_CAR, requestedScale = 2f)
        assertScale(2f, 200)
    }

    @Test
    fun changingDeviceConfigurationReclampsTheAlreadyLoadedPreference() {
        render(smallestWidth = 600, uiMode = Configuration.UI_MODE_TYPE_NORMAL, requestedScale = 2f)
        assertScale(2f, 200)

        composeRule.runOnIdle { smallestWidth.intValue = 599 }

        assertScale(1.2f, 120)
    }

    @Test
    @Config(qualifiers = "sw390dp-w390dp-h844dp")
    fun oneRepositorySubscriptionRestoresStoredTwoTimesAcrossPhoneAndCarModeChanges() {
        val savedPreferences = runBlocking { application.dataStore.data.first() }
        try {
            runBlocking { AutoSettingsRepository(application).setUiDensityScale(2f) }
            val starts = AtomicInteger()
            val emittedScale = AtomicReference<Float?>(null)
            val requestedScale = SettingsRepository(application).uiDensityScaleFlow
                .onStart { starts.incrementAndGet() }
                .onEach { emittedScale.set(it) }
            render(390, Configuration.UI_MODE_TYPE_NORMAL, 1f, requestedScale)
            composeRule.waitUntil(timeoutMillis = 5_000) { emittedScale.get() != null }
            assertScale(1.2f, 120)

            composeRule.runOnIdle { deviceUiMode.intValue = Configuration.UI_MODE_TYPE_CAR }
            assertScale(2f, 200)
            composeRule.runOnIdle { deviceUiMode.intValue = Configuration.UI_MODE_TYPE_NORMAL }
            assertScale(1.2f, 120)
            composeRule.runOnIdle { deviceUiMode.intValue = Configuration.UI_MODE_TYPE_CAR }
            assertScale(2f, 200)
            composeRule.runOnIdle { assertEquals(1, starts.get()) }
            assertEquals(2f, runBlocking { application.dataStore.data.first()[AutoSettingsKeys.UI_DENSITY_SCALE] })
        } finally {
            runBlocking { application.dataStore.updateData { savedPreferences } }
        }
    }

    private fun assertScale(expectedDensity: Float, expectedWidth: Int) {
        composeRule.runOnIdle {
            assertEquals(expectedDensity, appliedDensity, 0f)
            assertEquals(1.15f, appliedFontScale, 0f)
            assertEquals(expectedWidth, childWidthPx)
            assertEquals(originalDensity, application.resources.displayMetrics.density, 0f)
            assertEquals(originalDensityDpi, application.resources.displayMetrics.densityDpi)
        }
    }

    private fun render(
        smallestWidth: Int,
        uiMode: Int,
        requestedScale: Float,
        scaleFlow: Flow<Float>? = null
    ) {
        this.smallestWidth.intValue = smallestWidth
        deviceUiMode.intValue = uiMode
        composeRule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                smallestScreenWidthDp = this@AppUiDensityRootTest.smallestWidth.intValue
                this.uiMode = deviceUiMode.intValue
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(1f, 1.15f)
            ) {
                val scale = scaleFlow?.collectAsStateWithLifecycle(initialValue = requestedScale)?.value ?: requestedScale
                AppUiDensityRoot(scale) {
                    val density = LocalDensity.current
                    SideEffect {
                        appliedDensity = density.density
                        appliedFontScale = density.fontScale
                    }
                    Box(Modifier.size(100.dp).onGloballyPositioned { childWidthPx = it.size.width })
                }
            }
        }
    }
}
