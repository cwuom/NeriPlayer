package moe.ouom.neriplayer.ui.navigation

import android.content.res.Configuration
import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.ui.component.playback.NeriMiniPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppMiniPlayerTabletActionsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val visible = mutableStateOf(true)
    private var expandCalls = 0
    private var sourceNavigationCalls = 0

    @Before
    fun checkHostAndRoomPreconditions() {
        assumeComposeHostAvailable()
        assertTrue("测试 Application 应已初始化现有业务依赖", AppContainer.isInitialized())
        assumeTrue("一起听入口测试仅使用无活动房间的测试环境",
            AppContainer.listenTogetherSessionManager.sessionState.value.roomId.isNullOrBlank())
    }

    @Test
    fun utilityButtonsOpenExistingPanelsAndBackAllowsOpeningTheNextPanel() {
        UiFailureDiagnostics.onFailure("tablet-mini-existing-panels") {
            render()
            listOf(Panel.Volume, Panel.ListenTogether, Panel.Queue, Panel.Volume).forEach { panel ->
                openPanel(panel)
                assertPanelContent(panel)
                capturePanel(panel)
                dismissPanelWithBack()
            }
            assertNoUnexpectedNavigation()
        }
    }

    @Test
    fun hidingMiniPlayerClosesEveryPanelWithoutReopeningItOnReturn() {
        UiFailureDiagnostics.onFailure("tablet-mini-panel-visibility") {
            render()
            Panel.entries.forEach { panel ->
                openPanel(panel)
                assertPanelContent(panel)
                composeRule.runOnIdle { visible.value = false }
                waitForNoPanel()
                composeRule.onNodeWithTag(PlayerTag).assertDoesNotExist()
                composeRule.runOnIdle { visible.value = true }
                composeRule.onNodeWithTag(PlayerTag).assertIsDisplayed()
                assertTrue("恢复 mini-player 不应重新打开${panel.label}", dialogs().isEmpty())
            }
            openPanel(Panel.Queue)
            assertPanelContent(Panel.Queue)
            dismissPanelWithBack()
            assertNoUnexpectedNavigation()
        }
    }

    @Test
    fun listenTogetherNicknameCanBeEditedLocallyAndDismissedWithoutJoining() {
        UiFailureDiagnostics.onFailure("tablet-mini-listen-panel-local-edit") {
            val sessionManager = AppContainer.listenTogetherSessionManager
            val originalConnection = sessionManager.sessionState.value.connectionState
            render()
            openPanel(Panel.ListenTogether)
            val nicknameField = composeRule.onNode(panelMarker(Panel.ListenTogether))
            nicknameField.assertIsEnabled().performClick()
            nicknameField.performTextReplacement("测试听友 mini-player")
            nicknameField.assertTextContains("测试听友 mini-player")
            Espresso.closeSoftKeyboard()
            dismissPanelWithBack()
            openPanel(Panel.Volume)
            assertPanelContent(Panel.Volume)
            dismissPanelWithBack()
            composeRule.runOnIdle {
                assertTrue("编辑昵称不应加入房间", sessionManager.sessionState.value.roomId.isNullOrBlank())
                assertEquals(originalConnection, sessionManager.sessionState.value.connectionState)
            }
            assertNoUnexpectedNavigation()
        }
    }

    private fun render() {
        composeRule.setContent {
            MaterialTheme {
                BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val hostDensity = LocalDensity.current
                    val scale = minOf(maxWidth.value / 1280f, maxHeight.value / 800f)
                    val density = Density(hostDensity.density * scale, fontScale = 1f)
                    val configuration = Configuration(LocalConfiguration.current).apply {
                        smallestScreenWidthDp = 800
                        screenWidthDp = 1280
                        screenHeightDp = 800
                        orientation = Configuration.ORIENTATION_LANDSCAPE
                    }
                    CompositionLocalProvider(LocalDensity provides density, LocalConfiguration provides configuration) {
                        Box(Modifier.requiredSize(1280.dp, 800.dp)) {
                            val controls = rememberMiniPlayerTabletActions(
                                visible = visible.value,
                                offlineMode = true,
                                onOpenCurrentPlaybackSource = { sourceNavigationCalls++ }
                            )
                            if (visible.value) {
                                NeriMiniPlayer(
                                    title = "平板 mini-player 面板接线",
                                    artist = "本地界面测试",
                                    coverUrl = null,
                                    isPlaying = false,
                                    modifier = Modifier.testTag(PlayerTag),
                                    onPlayPause = {},
                                    onPrevious = {},
                                    onNext = {},
                                    onExpand = { expandCalls++ },
                                    enableBlur = false,
                                    offlineMode = true,
                                    tabletControls = controls
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openPanel(panel: Panel) {
        assertTrue("打开${panel.label}前不应残留其它面板", dialogs().isEmpty())
        composeRule.onNodeWithTag(panel.buttonTag).assertIsDisplayed().performTouchInput { click() }
        composeRule.waitUntil(timeoutMillis = 5_000L) {
            composeRule.onAllNodes(panelMarker(panel)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(panelMarker(panel)).assertIsDisplayed()
        assertEquals("${panel.label}应只打开一个面板", 1, dialogs().size)
        Log.i(LogTag, "已打开${panel.label}，实际内容标记可见")
    }

    private fun assertPanelContent(panel: Panel) {
        composeRule.onNode(panelMarker(panel)).assertIsDisplayed()
        when (panel) {
            Panel.Volume -> composeRule.onNode(panelMarker(panel)).assertIsEnabled()
            Panel.ListenTogether -> composeRule.onNode(panelMarker(panel)).assertIsEnabled()
            Panel.Queue -> {
                val count = PlayerManager.currentQueueDisplaySnapshot().items.size
                val countLabel = composeRule.activity.resources.getQuantityString(
                    CoreCommonR.plurals.nowplaying_queue_count_format, count, count
                )
                composeRule.onNode(hasText(countLabel) and hasAnyAncestor(isDialog())).assertIsDisplayed()
            }
        }
    }

    private fun panelMarker(panel: Panel): SemanticsMatcher {
        val content = when (panel) {
            Panel.Volume -> SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)
            Panel.ListenTogether -> hasSetTextAction() and
                hasText(composeRule.activity.getString(CoreCommonR.string.listen_together_nickname))
            Panel.Queue -> hasText(composeRule.activity.getString(CoreCommonR.string.playlist_queue))
        }
        return content and hasAnyAncestor(isDialog())
    }

    private fun dismissPanelWithBack() {
        Espresso.pressBack()
        waitForNoPanel()
        composeRule.onNodeWithTag(PlayerTag).assertIsDisplayed()
    }

    private fun waitForNoPanel() {
        composeRule.waitUntil(timeoutMillis = 5_000L) { dialogs().isEmpty() }
    }

    private fun dialogs() = composeRule.onAllNodes(isDialog()).fetchSemanticsNodes()

    private fun assertNoUnexpectedNavigation() {
        composeRule.runOnIdle {
            assertEquals("功能按钮不应打开完整播放器", 0, expandCalls)
            assertEquals("打开队列本身不应跳转播放来源", 0, sourceNavigationCalls)
        }
    }

    private fun capturePanel(panel: Panel) {
        if (InstrumentationRegistry.getArguments().getString("captureUi") != "true") return
        val file = File(composeRule.activity.cacheDir, "tablet-mini-${panel.name.lowercase()}-panel.png")
        val bitmap = composeRule.onNode(isDialog()).captureToImage().asAndroidBitmap()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        Log.i(LogTag, "${panel.label}截图=${file.absolutePath}")
    }

    private enum class Panel(val buttonTag: String, val label: String) {
        Volume("miniPlayerVolume", "音量面板"),
        ListenTogether("miniPlayerListenTogether", "一起听面板"),
        Queue("miniPlayerQueue", "播放列表面板")
    }

    private companion object {
        const val PlayerTag = "tabletMiniPlayerActionsFixture"
        const val LogTag = "MiniPlayerActionsTest"
    }
}
