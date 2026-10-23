package moe.ouom.neriplayer.ui.component.navigation

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.ui.effect.glass.AdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.LocalAdvancedGlassController
import moe.ouom.neriplayer.ui.effect.glass.resolveLiquidGlassDeviceAllowed
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 33)
class CleanTabLensRenderingTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val enabledController = AdvancedGlassController(
        sdkInt = Build.VERSION.SDK_INT,
        advancedBlurEnabled = true,
        enhancedAdvancedBlurEnabled = true,
        backendReady = true,
        liquidGlassRequested = true
    )
    private val controller = mutableStateOf(enabledController)
    private val tabDrag = CleanTabDragState()

    @Before
    fun setUp() = assumeComposeHostAvailable()

    private fun showPressedIndicator() {
        var hostCoordinates: LayoutCoordinates? = null
        val pressedFrameCommitted = AtomicBoolean(false)
        composeRule.setContent {
            val view = LocalView.current
            val barLayer = rememberGraphicsLayer()
            val barHeightPx = with(LocalDensity.current) { 64.dp.toPx() }
            // 初始未按压帧不能提前完成等待，按压状态生效后再监听硬件提交
            if (tabDrag.dragProgress.value == 1f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                DisposableEffect(view) {
                    val observer = view.viewTreeObserver
                    val callback = Runnable { pressedFrameCommitted.set(true) }
                    observer.registerFrameCommitCallback(callback)
                    view.invalidate()
                    onDispose {
                        if (observer.isAlive) observer.unregisterFrameCommitCallback(callback)
                    }
                }
            }
            CompositionLocalProvider(LocalAdvancedGlassController provides controller.value) {
                MaterialTheme(colorScheme = lightColorScheme(
                    primary = Color.Red,
                    onSurface = Color.Black,
                    surfaceContainerLow = Color.White
                )) {
                    Box(
                        Modifier.size(180.dp, 128.dp).testTag("lensFixture")
                            .background(Color.Blue)
                            .onGloballyPositioned { hostCoordinates = it }
                    ) {
                        Box(
                            Modifier.offset(24.dp, 24.dp).size(100.dp, 64.dp)
                                .onGloballyPositioned {
                                    tabDrag.bar = it
                                    tabDrag.reportItem(0, it)
                                }
                        )
                        key(tabDrag.layoutVersion) {
                            CleanTabIndicator(tabDrag, { hostCoordinates }, barLayer, barHeightPx, dark = false)
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            runBlocking {
                tabDrag.pillCenterX.snapTo(requireNotNull(tabDrag.bounds(0)).center.x)
                tabDrag.dragProgress.snapTo(1f)
            }
        }
        composeRule.waitForIdle()
        composeRule.waitUntil(timeoutMillis = 5_000L) { pressedFrameCommitted.get() }
    }

    private fun brightestRimPixel(): Float {
        val pixels = composeRule.onNodeWithTag("lensFixture").captureToImage().toPixelMap()
        // 只取伸出底栏的顶部高光，避免把底层玻璃色层的变化当成镜片降级
        val x = with(composeRule.density) { 74.dp.roundToPx() }
        val top = with(composeRule.density) { 18.dp.roundToPx() }
        val bottom = with(composeRule.density) { 22.dp.roundToPx() }
        return (top..bottom).maxOf { y -> pixels[x, y].green }
    }

    private fun assertRimRemoved(message: String) {
        val brightness = brightestRimPixel()
        assertTrue("$message: rim green channel was $brightness", brightness < 0.5f)
    }

    private fun assertRimVisible() {
        val brightness = brightestRimPixel()
        assertTrue("the pressed glass lens must show its bright rim: $brightness", brightness > 0.7f)
    }

    @Test
    fun turningLiquidGlassOffRemovesPressedRim() = UiFailureDiagnostics.onFailure("clean-tab-lens-toggle") {
        showPressedIndicator()
        assertRimVisible()

        composeRule.runOnIdle { controller.value = enabledController.copy(liquidGlassRequested = false) }
        composeRule.waitForIdle()

        assertRimRemoved("the disabled lens must lose its glass rim")
    }

    @Test
    fun deviceAndRenderingFallbacksRemovePressedRim() = UiFailureDiagnostics.onFailure("clean-tab-lens-fallbacks") {
        showPressedIndicator()
        assertRimVisible()
        val fallbacks = listOf(
            "battery saver" to enabledController.copy(liquidGlassRequested = resolveLiquidGlassDeviceAllowed(
                powerSaveMode = true, lowRamDevice = false, dimensityDevice = false,
                quality = AdvancedBlurQuality.Default
            )),
            "low RAM" to enabledController.copy(liquidGlassRequested = resolveLiquidGlassDeviceAllowed(
                powerSaveMode = false, lowRamDevice = true, dimensityDevice = false,
                quality = AdvancedBlurQuality.Default
            )),
            "Dimensity default quality" to enabledController.copy(liquidGlassRequested = resolveLiquidGlassDeviceAllowed(
                powerSaveMode = false, lowRamDevice = false, dimensityDevice = true,
                quality = AdvancedBlurQuality.Default
            )),
            "region-local quality" to enabledController.copy(advancedBlurQuality = AdvancedBlurQuality.Low),
            "backend failure" to enabledController.afterBackendFailure()
        )

        fallbacks.forEach { (name, fallback) ->
            composeRule.runOnIdle { controller.value = fallback }
            composeRule.waitForIdle()
            assertRimRemoved("$name must disable the glass lens effects")
        }
    }
}
