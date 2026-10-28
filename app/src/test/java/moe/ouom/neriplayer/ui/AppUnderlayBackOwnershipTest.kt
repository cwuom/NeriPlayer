package moe.ouom.neriplayer.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import kotlinx.coroutines.flow.MutableStateFlow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.navigation.LocalPredictiveBackPreviewEnabled
import moe.ouom.neriplayer.ui.navigation.MainTabLayerHost
import moe.ouom.neriplayer.ui.playback.visual.AppNowPlayingOverlay
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayBackground
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayCover
import moe.ouom.neriplayer.ui.playback.visual.NowPlayingOverlayTheme
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsPageTransitionHost
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35, 36], application = Application::class, qualifiers = "sw800dp-w1280dp-h800dp-land")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppUnderlayBackOwnershipTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var visible by mutableStateOf(false)
    private var mounted by mutableStateOf(false)
    private var mountSettings by mutableStateOf(false)
    private var settingsPage by mutableStateOf(SettingsPage.StorageCacheDetails)
    private var settingsBackRequests = 0
    private var closeRequests = 0
    private val queue = MutableStateFlow<List<SongItem>>(emptyList())
    private val eventOwner = object : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
    }
    private val input = DirectNavigationEventInput()

    @Test
    fun lateSettingsHandlerCannotConsumeThePlayersOrdinaryBack() =
        assertPlayerOwnsBack(previewEnabled = true, predictive = false)

    @Test
    fun lateSettingsHandlerCannotConsumeThePlayersPredictiveBack() =
        assertPlayerOwnsBack(previewEnabled = true, predictive = true)

    @Test
    fun lateSettingsHandlerCannotConsumeBackWhenPreviewIsDisabled() =
        assertPlayerOwnsBack(previewEnabled = false, predictive = true)

    @Test
    @Config(qualifiers = "sw360dp-w360dp-h800dp-port")
    fun lateSettingsHandlerCannotConsumeThePhonePlayersOrdinaryBack() =
        assertPlayerOwnsBack(previewEnabled = true, predictive = false)

    @Test
    @Config(qualifiers = "sw360dp-w360dp-h800dp-port")
    fun lateSettingsHandlerCannotConsumeThePhonePlayersBackWhenPreviewIsDisabled() =
        assertPlayerOwnsBack(previewEnabled = false, predictive = true)

    private fun assertPlayerOwnsBack(previewEnabled: Boolean, predictive: Boolean) {
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            DisposableEffect(Unit) {
                eventOwner.navigationEventDispatcher.addInput(input)
                onDispose { eventOwner.navigationEventDispatcher.dispose() }
            }
            CompositionLocalProvider(
                LocalPredictiveBackPreviewEnabled provides previewEnabled,
                LocalNavigationEventDispatcherOwner provides eventOwner
            ) {
                Box(Modifier.fillMaxSize()) {
                    AppUnderlayBackScope(enabled = !visible && !mounted) {
                        if (mountSettings) {
                            MainTabLayerHost(selectedRoute = "settings") {
                                SettingsPageTransitionHost(
                                    activePage = settingsPage,
                                    isolateAdvancedGlassTransitions = false,
                                    backEnabled = settingsPage == SettingsPage.StorageCacheDetails,
                                    onBack = {
                                        settingsBackRequests++
                                        settingsPage = SettingsPage.Storage
                                    }
                                ) {
                                    Box(Modifier.fillMaxSize().testTag(SettingsContent))
                                }
                            }
                        }
                    }
                    AppNowPlayingOverlay(
                        visible = visible,
                        cover = NowPlayingOverlayCover(null, null, null, 0),
                        queueFlow = queue,
                        theme = NowPlayingOverlayTheme(
                            false, null, "6750A4", PaletteStyle.TonalSpot,
                            ColorSpec.SpecVersion.Default, uiStyle = UiStyle.Clean
                        ),
                        background = NowPlayingOverlayBackground(false, 0f, 0f, false, true),
                        onVisibilityChanged = { mounted = it },
                        onClose = {
                            closeRequests++
                            visible = false
                        }
                    ) {
                        Box(Modifier.fillMaxSize().testTag(PlayerContent))
                    }
                }
            }
        }
        advance(32)
        composeRule.runOnIdle { visible = true }
        advance(400)
        composeRule.runOnIdle {
            assertTrue("晚挂设置前播放层必须已经挂载", mounted)
            assertTrue(eventOwner.navigationEventDispatcher.history.value.currentIndex >= 0)
        }
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertExists()
        // 旋转或延迟挂载的设置子页会晚于播放层注册返回处理器
        composeRule.runOnIdle { mountSettings = true }
        advance(32)
        composeRule.onNodeWithTag(SettingsContent, useUnmergedTree = true).assertExists()

        if (predictive) {
            startGesture()
            composeRule.runOnIdle { input.backCancelled() }
            advance(400)
            composeRule.runOnIdle {
                assertTrue(visible)
                assertEquals(0, closeRequests)
                assertEquals(0, settingsBackRequests)
            }
            startGesture()
        }
        composeRule.runOnIdle { input.backCompleted() }
        advance(400)

        composeRule.runOnIdle {
            assertEquals("底层设置不能抢走播放页的返回", 0, settingsBackRequests)
            assertEquals(SettingsPage.StorageCacheDetails, settingsPage)
            assertEquals(1, closeRequests)
            assertFalse(visible)
            assertFalse(mounted)
        }
        composeRule.onNodeWithTag(PlayerContent, useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag(SettingsContent, useUnmergedTree = true).assertExists()
        composeRule.runOnIdle { input.backCompleted() }
        advance(32)
        composeRule.runOnIdle {
            assertEquals("播放层卸载后设置应恢复返回所有权", 1, settingsBackRequests)
            assertEquals(SettingsPage.Storage, settingsPage)
        }
    }

    private fun startGesture() {
        composeRule.runOnIdle {
            input.backStarted(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT))
            input.backProgressed(NavigationEvent(swipeEdge = NavigationEvent.EDGE_LEFT, progress = 0.5f))
        }
        advance(32)
    }

    private fun advance(milliseconds: Long) {
        val deadline = composeRule.mainClock.currentTime + milliseconds
        while (composeRule.mainClock.currentTime < deadline) {
            composeRule.mainClock.advanceTimeByFrame()
            composeRule.waitForIdle()
        }
    }

    private companion object {
        const val PlayerContent = "underlay-back-player"
        const val SettingsContent = "underlay-back-settings"
    }
}
