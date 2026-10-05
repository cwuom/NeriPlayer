package moe.ouom.neriplayer.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlLayoutPreferences
import moe.ouom.neriplayer.data.model.settings.playback.PlaybackControlSize
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.FittedTestViewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StartupOnboardingLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun assumeDeviceUnlocked() {
        assumeComposeHostAvailable()
    }

    @Test
    fun shortLandscape_keepsContentBesideHeaderAndActions() {
        setLayout(width = 720.dp, height = 320.dp)

        val header = composeRule.onNodeWithTag("intro").fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot

        assertTrue("横屏内容应位于说明栏右侧", content.left >= header.right)
        assertTrue("横屏内容不应被说明挤到页面底部", content.top < header.bottom)
        assertTrue("内容应延伸到操作栏所在高度", content.bottom >= actions.top)
        composeRule.onNodeWithTag("next").assertIsDisplayed()
    }

    @Test
    fun shortLandscape_scrollingContentLeavesNextActionVisible() {
        setLayout(width = 720.dp, height = 320.dp)
        val initialActions = composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot

        composeRule.onNodeWithTag("last-option").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("next").assertIsDisplayed()
        assertEquals(
            "设置区域滚动不应移动下一步按钮",
            initialActions,
            composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot
        )
    }

    @Test
    fun narrowLandscape_largeFontKeepsScrollableContentAndNextVisible() {
        setLayout(width = 568.dp, height = 320.dp, fontScale = 1.3f)

        val content = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot
        assertTrue("窄横屏必须保留设置内容的可用高度", content.height > actions.height)
        composeRule.onNodeWithTag("last-option").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("next").assertIsDisplayed()
    }

    @Test
    fun portrait_keepsHeaderContentAndActionsInReadingOrder() {
        setLayout(width = 360.dp, height = 640.dp)

        val header = composeRule.onNodeWithTag("intro").fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot

        assertTrue("竖屏说明应位于内容上方", header.bottom < content.top)
        assertTrue("竖屏操作应位于内容下方", content.bottom < actions.top)
        composeRule.onNodeWithTag("next").assertIsDisplayed()
    }

    @Test
    fun tabletPortrait_firstFramePlacesSettingsBesideHeader() {
        // 缩小密度让竖屏平板的可用内容区完整落在当前测试宿主中
        setLayout(width = 800.dp, height = 1_000.dp, densityScale = 0.5f)

        val header = composeRule.onNodeWithTag("intro").fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        val actions = composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot

        assertTrue("竖屏平板首次显示就应使用双栏", content.left >= header.right)
        assertTrue("竖屏平板的说明不应挤压设置高度", content.top < header.bottom)
        assertTrue("操作应固定在说明栏内", actions.right <= content.left)
        composeRule.onNodeWithTag("next").assertIsDisplayed()
    }

    @Test
    fun mediumTabletPortrait_largeFontKeepsSettingsScrollableAndNextFixed() {
        setLayout(
            width = 600.dp,
            height = 900.dp,
            fontScale = 1.5f,
            densityScale = 0.5f,
            scrollContentHeight = 1_200.dp
        )
        val header = composeRule.onNodeWithTag("intro").fetchSemanticsNode().boundsInRoot
        val content = composeRule.onNodeWithTag("content").fetchSemanticsNode().boundsInRoot
        val initialActions = composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot
        assertTrue("中等竖屏平板也应显示独立设置栏", content.left >= header.right)

        composeRule.onNodeWithTag("last-option").assertIsNotDisplayed()
        composeRule.onNodeWithTag("last-option").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("next").assertIsDisplayed()
        assertEquals(
            "竖屏平板滚动设置时下一步应保持原位",
            initialActions,
            composeRule.onNodeWithTag("next").fetchSemanticsNode().boundsInRoot
        )
    }

    @Test
    fun shortLandscape_largeFontKeepsRealPermissionActionsReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mediaTitle = context.getString(CoreCommonR.string.onboarding_permission_media_title)
        val allowLabel = context.getString(CoreCommonR.string.onboarding_permission_allow)
        var mediaRequested = false

        setRealStepLayout(fontScale = 2f) {
            StartupPermissionContent(
                notificationPermissionSupported = true,
                notificationPermissionGranted = false,
                localMediaPermissionGranted = false,
                onRequestNotificationPermission = {},
                onRequestLocalMediaPermission = { mediaRequested = true }
            )
        }

        composeRule.onNodeWithText(mediaTitle).performScrollTo().assertIsDisplayed()
        composeRule.onAllNodesWithText(allowLabel)[1].performScrollTo().performClick()
        composeRule.onNodeWithTag("next").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue("大字体横屏仍能触发本地媒体权限操作", mediaRequested) }
    }

    @Test
    fun narrowLandscape_realPermissionActionsRemainReachableWithLargeFont() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val allowLabel = context.getString(CoreCommonR.string.onboarding_permission_allow)
        var mediaRequested = false

        setRealStepLayout(fontScale = 1.5f, width = 540.dp, height = 300.dp) {
            StartupPermissionContent(
                notificationPermissionSupported = true,
                notificationPermissionGranted = false,
                localMediaPermissionGranted = false,
                onRequestNotificationPermission = {},
                onRequestLocalMediaPermission = { mediaRequested = true }
            )
        }

        composeRule.onAllNodesWithText(allowLabel)[1].performScrollTo().performClick()
        composeRule.onNodeWithTag("next").assertIsDisplayed()
        composeRule.runOnIdle { assertTrue(mediaRequested) }
    }

    @Test
    fun shortLandscape_livePreviewKeepsControlChoicesAndNextReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val largeSizeLabel = context.getString(CoreCommonR.string.settings_playback_control_size_large)
        val selectedPreferences = mutableStateOf(PlaybackControlLayoutPreferences())
        val previewLifecycleOwner = object : LifecycleOwner {
            override val lifecycle = LifecycleRegistry(this)
        }
        // 暂停波形的生命周期帧循环，保留测试时钟自动处理重组和布局
        composeRule.runOnUiThread {
            previewLifecycleOwner.lifecycle.currentState = Lifecycle.State.STARTED
        }
        try {
            setRealStepLayout(fontScale = 1.5f) {
                CompositionLocalProvider(LocalLifecycleOwner provides previewLifecycleOwner) {
                    StartupPlaybackControlsContent(
                        preferences = selectedPreferences.value,
                        coverLyricFontScale = 1.4f,
                        onCoverLyricFontScaleChange = {},
                        onPreferencesChange = { selectedPreferences.value = it }
                    )
                }
            }
            composeRule.onNodeWithText(largeSizeLabel)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()

            composeRule.onNodeWithText(largeSizeLabel).assertIsSelected()
            composeRule.onNodeWithTag("next").assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(PlaybackControlSize.LARGE, selectedPreferences.value.nowPlayingSize)
            }
        } finally {
            composeRule.runOnUiThread {
                previewLifecycleOwner.lifecycle.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    private fun setRealStepLayout(
        fontScale: Float,
        width: Dp = 840.dp,
        height: Dp = 360.dp,
        content: @Composable () -> Unit
    ) {
        composeRule.setContent {
            MaterialTheme {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(baseDensity.density, fontScale = fontScale)
                ) {
                    FittedTestViewport(width, height, fontScale = fontScale) {
                        StartupOnboardingLayout(
                            header = {
                                Text(
                                    stringResource(CoreCommonR.string.onboarding_title),
                                    style = MaterialTheme.typography.headlineSmall
                                )
                                Text(
                                    stringResource(CoreCommonR.string.onboarding_subtitle),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            },
                            actions = {
                                Button(onClick = {}, modifier = Modifier.testTag("next")) {
                                    Text("下一步")
                                }
                            }
                        ) {
                            Column(
                                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                            ) {
                                content()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun setLayout(
        width: Dp,
        height: Dp,
        fontScale: Float = 1f,
        densityScale: Float = 1f,
        scrollContentHeight: Dp = 700.dp
    ) {
        composeRule.setContent {
            MaterialTheme {
                val baseDensity = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        baseDensity.density * densityScale,
                        fontScale = fontScale
                    )
                ) {
                    FittedTestViewport(width, height, fontScale = fontScale) {
                        StartupOnboardingLayout(
                            header = {
                                Text("引导说明", Modifier.height(160.dp).testTag("intro"))
                            },
                            actions = {
                                Button(onClick = {}, modifier = Modifier.testTag("next")) {
                                    Text("下一步")
                                }
                            }
                        ) {
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .testTag("content")
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text("第一项设置")
                                Spacer(Modifier.height(scrollContentHeight))
                                Text("最后一项设置", Modifier.testTag("last-option"))
                            }
                        }
                    }
                }
            }
        }
    }
}
