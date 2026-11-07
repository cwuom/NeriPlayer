package moe.ouom.neriplayer.ui.theme.reveal

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.component.ThemeModeActionButton
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ThemeRevealResponseRenderingTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() = assumeComposeHostAvailable()

    @Test
    fun switchingToDarkRevealsContentWhileTheSunIconIsStillAnimating() {
        assertRevealStartsPromptly(initiallyDark = false)
    }

    @Test
    fun switchingToLightRevealsContentWhileTheMoonIconIsStillAnimating() {
        assertRevealStartsPromptly(initiallyDark = true)
    }

    @Test
    fun missingSnapshotKeepsContentVisibleWhenSwitchingToDark() {
        assertMissingSnapshotKeepsContentVisible(initiallyDark = false)
    }

    @Test
    fun missingSnapshotKeepsContentVisibleWhenSwitchingToLight() {
        assertMissingSnapshotKeepsContentVisible(initiallyDark = true)
    }

    private fun assertMissingSnapshotKeepsContentVisible(initiallyDark: Boolean) {
        val dark = mutableStateOf(initiallyDark)
        val presentation = mutableStateOf<AppThemeRevealPresentation?>(null)
        val snapshot = mutableStateOf<ImageBitmap?>(null)
        val completedTokens = mutableListOf<Int>()
        var captureToken = 0
        var startRadiusPx = 0f
        lateinit var captureView: View
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val darkAction = context.getString(CoreCommonR.string.settings_theme_toggle_dark)
        val lightAction = context.getString(CoreCommonR.string.settings_theme_toggle_light)
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                val host = LocalView.current
                SideEffect { captureView = host.rootView }
                NeriTheme(
                    followSystemDark = false,
                    forceDark = dark.value,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = UiStyle.Clean,
                    animateColors = snapshot.value == null
                ) {
                    val background = MaterialTheme.colorScheme.background
                    Box(
                        Modifier.requiredSize(240.dp).background(background).testTag(ViewportTag),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(Modifier.align(Alignment.TopStart).size(48.dp).background(Color.Red))
                        Box(Modifier.align(Alignment.TopEnd).size(48.dp).background(Color.Green))
                        Box(Modifier.align(Alignment.BottomStart).size(48.dp).background(Color.Blue))
                        ThemeModeActionButton(
                            isDarkTheme = dark.value,
                            onToggleRequest = { origin, radius ->
                                if (presentation.value == null) {
                                    captureToken += 1
                                    startRadiusPx = radius
                                    presentation.value = AppThemeRevealPresentation(
                                        originInWindow = origin,
                                        fallbackColorArgb = background.toArgb(),
                                        captureToken = captureToken,
                                        reverse = shouldReverseThemeReveal(nextDark = !dark.value)
                                    )
                                    dark.value = !dark.value
                                }
                            }
                        )
                        AppThemeRevealOverlayHost(
                            presentation = presentation.value,
                            snapshot = snapshot.value,
                            startRadiusPx = startRadiusPx,
                            onFinished = { token ->
                                completedTokens += token
                                if (presentation.value?.captureToken == token) presentation.value = null
                            }
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            var windowCaptureCalls = 0
            snapshot.value = runBlocking {
                withTimeout(5_000L) {
                    withContext(Dispatchers.Main.immediate) {
                        assertTrue("the fallback must come from a real hardware window",
                            captureView.isAttachedToWindow && captureView.isHardwareAccelerated)
                        captureThemeRevealSnapshot(null, captureView, windowCapture = {
                            windowCaptureCalls += 1
                            null
                        })
                    }
                }
            }
            assertEquals("the failed window capture must execute", 1, windowCaptureCalls)
            assertNull("the host must receive the actual missing snapshot", snapshot.value)
            assertFixtureContentVisible("before switching")

            composeRule.onNodeWithContentDescription(if (initiallyDark) lightAction else darkAction).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            assertFixtureContentVisible("handoff")
            composeRule.mainClock.advanceTimeBy(80L)
            assertFixtureContentVisible("intermediate")
            composeRule.mainClock.advanceTimeBy(1_000L)
            assertFixtureContentVisible("settled")
            composeRule.runOnIdle {
                assertEquals(!initiallyDark, dark.value)
                assertEquals("the missing snapshot must release its token once", listOf(1), completedTokens)
                assertNull(presentation.value)
            }

            composeRule.onNodeWithContentDescription(if (initiallyDark) darkAction else lightAction).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            assertFixtureContentVisible("second handoff")
            composeRule.mainClock.advanceTimeBy(1_000L)
            assertFixtureContentVisible("second settled")
            composeRule.runOnIdle {
                assertEquals("the next switch must be accepted", 2, captureToken)
                assertEquals(initiallyDark, dark.value)
                assertEquals("each switch must finish exactly once", listOf(1, 2), completedTokens)
                assertNull(presentation.value)
            }
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvance
        }
    }

    private fun assertFixtureContentVisible(stage: String) {
        val pixels = composeRule.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
        assertColor("red content must stay visible: $stage", Color.Red,
            pixels[pixels.width / 10, pixels.height / 10])
        assertColor("green content must stay visible: $stage", Color.Green,
            pixels[pixels.width * 9 / 10, pixels.height / 10])
        assertColor("blue content must stay visible: $stage", Color.Blue,
            pixels[pixels.width / 10, pixels.height * 9 / 10])
    }

    private fun assertRevealStartsPromptly(initiallyDark: Boolean) {
        val dark = mutableStateOf(initiallyDark)
        val presentation = mutableStateOf<AppThemeRevealPresentation?>(null)
        var snapshot: ImageBitmap? = null
        var startRadiusPx = 0f
        var viewportOriginInWindow = Offset.Zero
        var background = Color.Unspecified
        var finishCallbacks = 0
        val description = InstrumentationRegistry.getInstrumentation().targetContext.getString(
            if (initiallyDark) CoreCommonR.string.settings_theme_toggle_light
            else CoreCommonR.string.settings_theme_toggle_dark
        )
        val autoAdvance = composeRule.mainClock.autoAdvance
        composeRule.mainClock.autoAdvance = false
        try {
            composeRule.setContent {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = dark.value,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = UiStyle.Clean,
                    animateColors = false
                ) {
                    background = MaterialTheme.colorScheme.background
                    Box(
                        Modifier.requiredSize(240.dp).background(background).testTag(ViewportTag)
                            .onGloballyPositioned { viewportOriginInWindow = it.positionInWindow() },
                        contentAlignment = Alignment.Center
                    ) {
                        ThemeModeActionButton(
                            isDarkTheme = dark.value,
                            onToggleRequest = { origin, radius ->
                                startRadiusPx = radius
                                presentation.value = AppThemeRevealPresentation(
                                    originInWindow = origin,
                                    fallbackColorArgb = background.toArgb(),
                                    captureToken = 1,
                                    reverse = shouldReverseThemeReveal(nextDark = !dark.value)
                                )
                                dark.value = !dark.value
                            }
                        )
                        AppThemeRevealOverlayHost(
                            presentation = presentation.value,
                            snapshot = snapshot,
                            startRadiusPx = startRadiusPx,
                            onFinished = {
                                finishCallbacks += 1
                                presentation.value = null
                            }
                        )
                    }
                }
            }
            composeRule.waitForIdle()
            val oldFrame = composeRule.onNodeWithTag(ViewportTag).captureToImage()
            composeRule.runOnIdle { snapshot = oldFrame }
            composeRule.onNodeWithContentDescription(description).performClick()
            composeRule.mainClock.advanceTimeByFrame()
            val firstFrame = composeRule.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
            val pixelsPerDp = firstFrame.width / 240f
            val origin = checkNotNull(presentation.value).originInWindow - viewportOriginInWindow
            val sampleX = if (initiallyDark) pixelsPerDp.toInt()
                else (origin.x + startRadiusPx + 2f * pixelsPerDp).toInt()
            val sampleY = if (initiallyDark) pixelsPerDp.toInt() else origin.y.toInt()
            check(sampleX in 0 until firstFrame.width && sampleY in 0 until firstFrame.height)
            val oldColor = oldFrame.toPixelMap()[sampleX, sampleY]
            assertColor("首次交接必须保留旧主题快照，origin=$origin radius=$startRadiusPx sample=($sampleX,$sampleY)",
                oldColor, firstFrame[sampleX, sampleY])

            // 使用已捕获画面的真实交接层，隔离窗口捕获耗时；此时按钮的 620ms 图标动画仍在进行
            composeRule.mainClock.advanceTimeBy(80L)
            val earlyFrame = composeRule.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
            assertTrue("夹具必须切换到不同的主题底色", colorDifference(oldColor, background) > 0.5f)
            assertColor("揭示不应停住等待太阳或月亮图标动画", background, earlyFrame[sampleX, sampleY])
            composeRule.runOnIdle { assertEquals("揭示仍应继续播放", 0, finishCallbacks) }

            composeRule.mainClock.advanceTimeBy(1_000L)
            composeRule.runOnIdle { assertEquals("完整揭示应正常结束并释放锁", 1, finishCallbacks) }
            val finalFrame = composeRule.onNodeWithTag(ViewportTag).captureToImage().toPixelMap()
            assertColor("结束后应显示新主题", background, finalFrame[0, 0])
        } finally {
            composeRule.mainClock.autoAdvance = autoAdvance
        }
    }

    private fun assertColor(message: String, expected: Color, actual: Color) {
        assertTrue("$message: expected=$expected actual=$actual", colorDifference(expected, actual) < 0.15f)
    }

    private fun colorDifference(first: Color, second: Color): Float =
        maxOf(abs(first.red - second.red), abs(first.green - second.green), abs(first.blue - second.blue))

    private companion object {
        const val ViewportTag = "theme-reveal-response-viewport"
    }
}
