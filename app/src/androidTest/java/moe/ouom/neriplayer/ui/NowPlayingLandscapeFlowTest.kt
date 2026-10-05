package moe.ouom.neriplayer.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.toSize
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.stats.flushPlaybackStatsPendingWrites
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.data.local.media.LocalMediaSupport
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.data.stats.PlaybackStatsCursor
import moe.ouom.neriplayer.data.stats.PlaybackStatsQuery
import moe.ouom.neriplayer.data.stats.PlaybackStatsRepository
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.grantRuntimePermissions
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.testutil.playbackRuntimePermissions
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

@RunWith(AndroidJUnit4::class)
class NowPlayingLandscapeFlowTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun selectedSettingsSearch_opensRealLandscapePlayerWithoutSelectionOverlay() {
        assumeComposeHostAvailable()
        assumeTrue("仅在 API 35 模拟器运行真实播放流程", Build.VERSION.SDK_INT == 35 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assumeTrue("需要至少 480dp 宽的横屏模拟器", context.resources.configuration.orientation ==
            Configuration.ORIENTATION_LANDSCAPE && context.resources.configuration.screenWidthDp >= 480)
        val phone = context.resources.configuration.smallestScreenWidthDp < 600
        val settings = SettingsRepository(context)
        val autoSettings = AutoSettingsRepository(context)
        val saved = runBlocking { SavedSettings.read(settings) }
        val savedQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val automation = instrumentation.uiAutomation
        val originalServiceFlags = automation.serviceInfo.flags
        val fixtureDirectory = File(context.cacheDir, "landscape-flow-${System.nanoTime()}")
        check(fixtureDirectory.mkdirs())
        var scenario: ActivityScenario<MainActivity>? = null
        try {
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            runBlocking {
                val warning = DebugBuildWarningRepository(context)
                warning.acknowledge()
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setDefaultStartDestination(Destinations.Settings.route)
                settings.setUiDensityScale(1f)
                settings.setAdvancedLyricsEnabled(false)
                settings.setShowLyricTranslation(true)
                settings.setLyricTranslationUsePhonetic(false)
                settings.setDynamicColor(true)
                settings.setNowPlayingDynamicBackgroundEnabled(true)
                settings.setNowPlayingCoverBlurBackgroundEnabled(false)
                autoSettings.setNowPlayingCoverLyricsEnabled(true)
                autoSettings.setNowPlayingProgressShowQualitySwitch(true)
                autoSettings.setNowPlayingProgressShowAudioCodec(true)
                autoSettings.setNowPlayingProgressShowAudioSpec(true)
                assertTrue(settings.disclaimerAcceptedFlow.filterNotNull().first())
                assertTrue(settings.startupOnboardingCompletedFlow.filterNotNull().first())
                assertTrue(settings.defaultStartDestinationFlow.first() == Destinations.Settings.route)
                assertTrue(warning.isAcknowledged())
            }
            grantRuntimePermissions(*playbackRuntimePermissions())
            scenario = ActivityScenario.launch(MainActivity::class.java)
            var playLabel = ""
            var backLabel = ""
            var warningTitle = ""
            var copyLabel = ""
            var cutLabel = ""
            scenario.onActivity { activity ->
                if (phone) {
                    assertTrue("手机设置页应锁定竖屏", activity.requestedOrientation ==
                        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
                } else {
                    assertTrue("平板主界面应保留系统方向", activity.requestedOrientation ==
                        ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
                }
                val localizedContext = LanguageManager.localizedContext(
                    activity, LanguageManager.getCurrentLanguage(activity)
                )
                playLabel = localizedContext.getString(CoreCommonR.string.player_play)
                backLabel = localizedContext.getString(CoreCommonR.string.action_back)
                warningTitle = localizedContext.getString(R.string.debug_build_warning_title)
                copyLabel = localizedContext.getString(android.R.string.copy)
                cutLabel = localizedContext.getString(android.R.string.cut)
            }
            composeRule.waitUntil(STARTUP_TIMEOUT_MS) {
                dismissSystemImmersiveConfirmation(automation)
                composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
                    .fetchSemanticsNodes().size == 1 &&
                    composeRule.onAllNodesWithText(warningTitle).fetchSemanticsNodes().isEmpty()
            }
            if (phone) waitForPhonePortrait(scenario)
            else composeRule.onNodeWithTag("appNavigationRail").assertIsDisplayed()
            val song = createLocalSong(context, fixtureDirectory)
            scenario.onActivity { activity ->
                PlayerManager.release()
                PlayerManager.initialize(activity.application)
                PlayerManager.playPlaylist(listOf(song), startIndex = 0)
            }
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                val currentSong = PlayerManager.currentSongFlow.value
                currentSong != null && currentSong.id == song.id && currentSong.mediaUri == song.mediaUri &&
                    currentSong.matchedLyric?.contains(LYRIC_LINE) == true && PlayerManager.isPlayingFlow.value
            }
            scenario.onActivity {
                PlayerManager.pause()
                PlayerManager.seekTo(14_000L)
            }
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                !PlayerManager.playbackControlPlayingFlow.value &&
                    PlayerManager.playbackPositionFlow.value in 12_000L..16_000L
            }
            val songName = checkNotNull(PlayerManager.currentSongFlow.value).displayName()
            composeRule.waitUntil(STARTUP_TIMEOUT_MS) {
                composeRule.onAllNodesWithText(songName).fetchSemanticsNodes().isNotEmpty()
            }
            if (!phone) {
                val rail = composeRule.onNodeWithTag("appNavigationRail").fetchSemanticsNode().boundsInRoot
                val miniPlayerTitle = composeRule.onNodeWithText(songName).fetchSemanticsNode().boundsInRoot
                assertTrue("迷你播放器应位于侧栏右侧的主内容区", miniPlayerTitle.left >= rail.right)
                composeRule.onNodeWithTag("miniPlayerPrevious").assertIsDisplayed()
                composeRule.onNodeWithTag("miniPlayerNext").assertIsDisplayed()
            } else {
                composeRule.onNodeWithTag("miniPlayerPrevious").assertDoesNotExist()
                composeRule.onNodeWithTag("miniPlayerNext").assertDoesNotExist()
            }
            val search = composeRule.onNode(hasSetTextAction())
            composeRule.waitUntil(STARTUP_TIMEOUT_MS) {
                dismissSystemImmersiveConfirmation(automation)
                var focused = false
                scenario.onActivity { focused = it.hasWindowFocus() }
                focused
            }
            capture(context, "settings-before-input")
            scenario.onActivity { activity ->
                Log.i("LandscapeFlowTest", "beforeInput requested=${activity.requestedOrientation} " +
                    "orientation=${activity.resources.configuration.orientation} " +
                    "sw=${activity.resources.configuration.smallestScreenWidthDp} " +
                    "width=${activity.resources.configuration.screenWidthDp} " +
                    "height=${activity.resources.configuration.screenHeightDp}")
            }
            search.performClick().performTextReplacement("lyrics")
            instrumentation.uiAutomation.waitForIdle(500, 5_000)
            capture(context, "settings-before-selection")
            search.performTouchInput { longClick(Offset(12f, center.y)) }
            search.assertIsFocused()
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                !search.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].collapsed &&
                    nativeSelectionMenuShown(setOf(copyLabel, cutLabel))
            }
            capture(context, "settings-selection")

            // 触摸真实迷你播放器，保留系统选择菜单在进入前的状态
            // 动态背景持续请求帧，播放阶段由测试推进时钟，避免等待动画永久空闲
            composeRule.mainClock.autoAdvance = false
            if (phone) {
                // 手机键盘覆盖迷你播放器，先收起键盘但保留焦点和系统选择菜单
                scenario.onActivity { activity ->
                    WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        .hide(WindowInsetsCompat.Type.ime())
                }
                composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                    composeRule.mainClock.advanceTimeBy(64L)
                    automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                }
                search.assertIsFocused()
                assertTrue(!search.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].collapsed)
                assertTrue(nativeSelectionMenuShown(setOf(copyLabel, cutLabel)))
                capture(context, "settings-selection-keyboard-hidden")
            }
            composeRule.onNodeWithText(songName, useUnmergedTree = true)
                .assertIsDisplayed().performNativeClick()
            composeRule.mainClock.advanceTimeBy(500L)
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                composeRule.onAllNodesWithTag("nowPlayingWideLayout", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.waitForIdle()
            waitForLandscapeCoverReady(songName)
            search.assertIsNotFocused()
            assertTrue(search.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].collapsed)
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                !nativeSelectionMenuShown(setOf(copyLabel, cutLabel))
            }
            composeRule.onAllNodes(isPopup()).assertCountEquals(0)
            if (!phone) composeRule.onAllNodesWithTag("appNavigationRail").assertCountEquals(0)
            composeRule.onNodeWithTag("nowPlayingWideLayout", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("nowPlayingPlayerPane", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("nowPlayingLyricPane", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithContentDescription(playLabel, useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithTag("nowPlayingBackButton").assertContentDescriptionEquals(backLabel)
            composeRule.onNodeWithContentDescription(songName, useUnmergedTree = true).assertIsDisplayed()
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                val lines = composeRule.onAllNodesWithText(LYRIC_LINE, useUnmergedTree = true)
                lines.fetchSemanticsNodes().indices.any { lines[it].isDisplayed() }
            }
            assertPlaybackInformationVisible(!phone, "nowPlayingLandscapeCoverPage")
            capture(context, "player")
            if (!phone) verifyLandscapeLyricsAction(context, "nowPlayingLandscapeCoverPage")
            verifyLandscapeLyricsPage(context, phone)

            if (phone) {
                verifyCompactEditingWithKeyboard(context, songName)
                verifyPhonePortraitReturnAndReopening(scenario, context, songName)
            } else {
                verifyTabletPortraitAndLandscape(scenario, context, songName, playLabel)
            }

            if (phone) Espresso.pressBack()
            else composeRule.onNodeWithTag("nowPlayingBackButton").performClick()
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                val fields = composeRule.onAllNodes(hasSetTextAction())
                composeRule.onAllNodesWithTag("nowPlayingBackButton").fetchSemanticsNodes().isEmpty() &&
                    composeRule.onAllNodesWithTag("nowPlayingWideLayout", useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty() && fields.fetchSemanticsNodes().size == 1 &&
                    fields[0].isDisplayed()
            }
            composeRule.onNode(hasSetTextAction()).assertIsNotFocused()
            assertRequestedOrientation(scenario, if (phone) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            composeRule.onNodeWithText("lyrics").assertIsDisplayed()
            if (!phone) composeRule.onNodeWithTag("appNavigationRail").assertIsDisplayed()
            assertTrue(!nativeSelectionMenuShown(setOf(copyLabel, cutLabel)))
            capture(context, "settings-return")
        } catch (failure: Throwable) {
            runCatching { capture(context, "failure") }
                .onFailure { Log.w("LandscapeFlowTest", "failure screenshot unavailable", it) }
            runCatching { composeRule.onRoot(useUnmergedTree = true).printToLog("LandscapeFlowTest") }
                .onFailure { Log.w("LandscapeFlowTest", "semantics unavailable", it) }
            UiFailureDiagnostics.record("now-playing-landscape-flow", failure)
            throw failure
        } finally {
            try {
                instrumentation.runOnMainSync { PlayerManager.release() }
                scenario?.close()
            } finally {
                composeRule.mainClock.autoAdvance = true
                try {
                    if (!phone) assertTrue(automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
                    automation.serviceInfo = automation.serviceInfo.apply { flags = originalServiceFlags }
                } finally {
                    try {
                        runBlocking { removeFixturePlaybackStats(context) }
                    } finally {
                        try {
                            runBlocking { savedQueue.restore(context) }
                        } finally {
                            runBlocking { saved.restore(settings, autoSettings) }
                            check(fixtureDirectory.deleteRecursively()) { "未能清理合成本地媒体" }
                        }
                    }
                }
            }
        }
    }

    private data class SavedPlaybackQueue(
        val roomPrimary: Boolean,
        val state: PersistedState?,
        val legacyFiles: Map<String, ByteArray?>
    ) {
        suspend fun restore(context: Context) {
            val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
            val fixtureRoot = File(context.cacheDir, "landscape-flow-").path
            val onlySyntheticQueue = state?.playlist?.takeIf { it.isNotEmpty() }?.all { song ->
                song.localFilePath?.startsWith(fixtureRoot) == true
            } == true
            if (roomPrimary && state != null && !onlySyntheticQueue) store.replaceSnapshot(state)
            else store.clear()
            legacyFiles.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes != null) file.writeBytes(bytes)
                else check(!file.exists() || file.delete()) { "未能清理测试播放队列文件" }
            }
            if (!roomPrimary) store.markLegacyJsonPrimary(System.currentTimeMillis())
        }

        companion object {
            suspend fun read(context: Context): SavedPlaybackQueue {
                val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
                return SavedPlaybackQueue(
                    roomPrimary = store.isRoomPrimary(),
                    state = store.readIfRoomPrimary(),
                    legacyFiles = listOf("last_playlist.json", "last_playback_state.json").associateWith { name ->
                        File(context.filesDir, name).takeIf(File::exists)?.readBytes()
                    }
                )
            }
        }
    }

    private suspend fun removeFixturePlaybackStats(context: Context) {
        flushPlaybackStatsPendingWrites(context)
        val repository = PlaybackStatsRepository.getInstance(context)
        val fixturePath = Regex(Regex.escape(File(context.cacheDir, "landscape-flow-").path) +
            "-?\\d+/landscape\\.wav")
        val query = PlaybackStatsQuery()
        val keys = mutableSetOf<String>()
        var cursor: PlaybackStatsCursor? = null
        do {
            val page = repository.readPage(query, after = cursor)
            page.tracks.filter { stat ->
                stat.localFilePath?.let(fixturePath::matches) == true &&
                    stat.mediaUri == stat.localFilePath
            }.mapTo(keys) { it.identityKey }
            cursor = page.nextCursor
        } while (cursor != null)
        repository.removeTracks(keys)
        withTimeout(10_000L) {
            while (keys.any { repository.getStatForTrack(it) != null }) delay(50L)
        }
        Log.i("LandscapeFlowTest", "removed synthetic playback statistics=${keys.size}")
    }

    private fun verifyLandscapeLyricsPage(context: Context, phone: Boolean) {
        val playerBounds = composeRule.onNodeWithTag("nowPlayingPlayerPane", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val lyricBounds = composeRule.onNodeWithTag("nowPlayingLyricPane", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("nowPlayingPlayerPane", useUnmergedTree = true)
            .performTouchInput { swipeLeft() }
        waitForLandscapePage("nowPlayingLandscapeLyricsPage", "nowPlayingLandscapeCoverPage")
        composeRule.onNodeWithTag("nowPlayingWideLayout", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(playerBounds, composeRule.onNodeWithTag("nowPlayingPlayerPane", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot)
        assertEquals(lyricBounds, composeRule.onNodeWithTag("nowPlayingLyricPane", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            val lines = composeRule.onAllNodesWithText(LYRIC_LINE, useUnmergedTree = true)
            lines.fetchSemanticsNodes().indices.any { lines[it].isDisplayed() }
        }
        assertPlaybackInformationVisible(!phone, "nowPlayingLandscapeLyricsPage")
        if (!phone) verifyLandscapeLyricsAction(context, "nowPlayingLandscapeLyricsPage")
        capture(context, "lyrics-page")
        composeRule.onNodeWithTag("nowPlayingPlayerPane", useUnmergedTree = true)
            .performTouchInput { swipeRight() }
        waitForLandscapePage("nowPlayingLandscapeCoverPage", "nowPlayingLandscapeLyricsPage")
        assertPlaybackInformationVisible(!phone, "nowPlayingLandscapeCoverPage")
        capture(context, "cover-return")
    }

    private fun verifyLandscapeLyricsAction(context: Context, pageTag: String) {
        val localized = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))
        val title = localized.getString(CoreCommonR.string.lyrics_adjust_behavior)
        val translation = localized.getString(CoreCommonR.string.settings_show_lyric_translation)
        val phonetics = localized.getString(CoreCommonR.string.lyrics_translation_use_phonetic)
        val doneText = localized.getString(CoreCommonR.string.action_done)
        val page = composeRule.onNodeWithTag(pageTag, useUnmergedTree = true)
        val pageBounds = page.assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("nowPlayingLyricsAction")
            .assertContentDescriptionEquals(title).assertIsDisplayed().performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            listOf(title, translation, phonetics, doneText).all { label ->
                val nodes = composeRule.onAllNodesWithText(label)
                nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
            }
        }
        composeRule.onNodeWithText(translation).assertIsDisplayed()
        composeRule.onNodeWithText(phonetics).assertIsDisplayed()
        if (pageTag == "nowPlayingLandscapeCoverPage") {
            val settings = SettingsRepository(context)
            composeRule.onNodeWithText(translation).performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.mainClock.advanceTimeBy(64L)
                !runBlocking { settings.showLyricTranslationFlow.first() }
            }
            composeRule.onNodeWithText(translation).performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.mainClock.advanceTimeBy(64L)
                runBlocking { settings.showLyricTranslationFlow.first() }
            }
            composeRule.onNodeWithText(phonetics).performClick()
            composeRule.waitUntil(5_000L) {
                composeRule.mainClock.advanceTimeBy(64L)
                runBlocking { settings.lyricTranslationUsePhoneticFlow.first() }
            }
        }
        val done = composeRule.onNodeWithText(doneText)
        done.assertIsDisplayed()
        capture(context, "lyrics-adjust-$pageTag")
        done.performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("调整歌词不能切换当前播放页", pageBounds, page.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
        composeRule.onNodeWithTag(if (pageTag == "nowPlayingLandscapeCoverPage") "nowPlayingLandscapeLyricsPage"
            else "nowPlayingLandscapeCoverPage", useUnmergedTree = true).assertDoesNotExist()
        composeRule.onNodeWithTag("nowPlayingLyricsAction").performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithText(doneText).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.mainClock.advanceTimeBy(500L)
        Espresso.pressBack()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("返回应只关闭歌词调整", pageBounds, page.assertIsDisplayed().fetchSemanticsNode().boundsInRoot)
    }

    private fun assertPlaybackInformationVisible(visible: Boolean, pageTag: String? = null) {
        // 本地歌曲不一定有音质标签，使用播放器实际探测出的编码和规格
        var labels: List<String>? = null
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            val audioInfo = PlayerManager.currentPlaybackAudioInfoFlow.value
            val codec = audioInfo?.codecLabel?.takeIf { it.isNotBlank() }
            val spec = audioInfo?.specLabel?.takeIf { it.isNotBlank() }
            if (codec != null && spec != null) {
                labels = listOf(codec, spec)
                true
            } else false
        }
        val expectedLabels = checkNotNull(labels)
        expectedLabels.forEach { label ->
            val matcher = if (visible && pageTag != null) {
                hasText(label) and hasAnyAncestor(hasTestTag(pageTag))
            } else hasText(label)
            val nodes = composeRule.onAllNodes(matcher, useUnmergedTree = true)
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                if (visible) nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
                else nodes.fetchSemanticsNodes().isEmpty()
            }
            if (visible) {
                assertTrue("实际播放信息应显示在当前页面: $label, page=$pageTag",
                    nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() })
            } else {
                nodes.assertCountEquals(0)
            }
        }
    }

    private fun waitForLandscapePage(visibleTag: String, removedTag: String) {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithTag(visibleTag, useUnmergedTree = true).fetchSemanticsNodes().size == 1 &&
                composeRule.onAllNodesWithTag(removedTag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun dismissSystemImmersiveConfirmation(automation: UiAutomation) {
        val root = automation.rootInActiveWindow ?: return
        if (root.packageName != "android" ||
            root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title").isEmpty()
        ) return
        root.findAccessibilityNodeInfosByViewId("android:id/ok").firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun verifyCompactEditingWithKeyboard(context: Context, songName: String) {
        val localizedContext = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))
        val editLabel = localizedContext.getString(CoreCommonR.string.music_edit_info)
        val titleLabel = localizedContext.getString(CoreCommonR.string.music_edit_title)
        val cancelLabel = localizedContext.getString(CoreCommonR.string.action_cancel)
        composeRule.onNodeWithContentDescription(localizedContext.getString(CoreCommonR.string.nowplaying_more_options))
            .performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.onNodeWithText(editLabel).performScrollTo().performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithTag("song-edit-layout").fetchSemanticsNodes().size == 1
        }
        composeRule.mainClock.advanceTimeBy(500L)
        capture(context, "phone-edit-sheet")
        val titleField = composeRule.onNode(hasSetTextAction() and hasText(titleLabel))
        scrollPausedEditorIntoView(titleField, "song-edit-fields")
            .assertIsDisplayed().performNativeClick().performTextReplacement("$songName draft")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        scrollPausedEditorIntoView(titleField, "song-edit-fields").assertIsDisplayed()
        val fields = composeRule.onNodeWithTag("song-edit-fields").fetchSemanticsNode().boundsInRoot
        val title = titleField.fetchSemanticsNode().boundsInRoot
        assertTrue("横屏键盘打开后标题输入框应完整可见",
            title.top >= fields.top - 1f && title.bottom <= fields.bottom + 1f)
        val sheet = composeRule.onNodeWithTag("song-edit-layout").fetchSemanticsNode().boundsInRoot
        composeRule.onNodeWithTag("song-edit-fields").performTouchInput { swipeDown() }
        composeRule.mainClock.advanceTimeBy(500L)
        assertEquals("表单下拉不能拖动实际 ModalBottomSheet", sheet,
            composeRule.onNodeWithTag("song-edit-layout").fetchSemanticsNode().boundsInRoot)
        val save = composeRule.onNodeWithText(localizedContext.getString(CoreCommonR.string.music_save_changes))
        if (!save.isDisplayed()) scrollPausedEditorIntoView(save, "song-edit-fields")
        save.assertIsDisplayed()
        capture(context, "phone-edit-keyboard")
        Espresso.pressBack()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        scrollPausedEditorIntoView(titleField, "song-edit-fields")
        assertEquals("关闭键盘不能丢失草稿", "$songName draft",
            titleField.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        verifyCompactLyricsEditingWithKeyboard(context, localizedContext)
        composeRule.onNodeWithText(cancelLabel).performClick()
        waitForLandscapeCoverReady(songName)
    }

    private fun verifyCompactLyricsEditingWithKeyboard(context: Context, localizedContext: Context) {
        scrollPausedEditorIntoView(
            composeRule.onNodeWithText(localizedContext.getString(CoreCommonR.string.music_edit_lyrics)),
            "song-edit-fields"
        ).performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithTag("lyrics-editor-input").fetchSemanticsNodes().size == 1
        }
        composeRule.mainClock.advanceTimeBy(500L)
        val input = composeRule.onNodeWithTag("lyrics-editor-input")
        scrollPausedEditorIntoView(input, "lyrics-editor-content")
            .assertIsDisplayed().performNativeClick().assertIsFocused()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            automation.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        val originalDraft = (1..24).joinToString("\n") { "[00:0${it % 10}.00] editable lyric $it" }
        input.performTextReplacement(originalDraft)
        scrollPausedEditorIntoView(input, "lyrics-editor-content").assertIsDisplayed()
        val viewport = composeRule.onNodeWithTag("lyrics-editor-layout").fetchSemanticsNode().boundsInRoot
        val visibleInput = input.fetchSemanticsNode().boundsInRoot.intersect(viewport)
        assertTrue("横屏键盘打开后歌词输入区必须仍可操作", visibleInput.height >= 48f * context.resources.displayMetrics.density)
        input.performTouchInput { swipeDown() }
        assertEquals(originalDraft, input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        capture(context, "phone-lyrics-editor-keyboard")
        Espresso.pressBack()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            automation.windows.none { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        }
        listOf(1, 2).forEach { tab ->
            selectPausedLyricsEditorTab(tab)
            scrollPausedEditorIntoView(input, "lyrics-editor-content").performTextReplacement("draft tab $tab")
            composeRule.mainClock.advanceTimeBy(64L)
        }
        selectPausedLyricsEditorTab(0)
        assertEquals("切换歌词类型不能丢失原文草稿", originalDraft,
            input.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        scrollPausedEditorIntoView(composeRule.onNodeWithTag("lyrics-editor-save"),
            "lyrics-editor-content").assertIsDisplayed()
        capture(context, "phone-lyrics-editor-actions")
        scrollPausedEditorIntoView(composeRule.onNodeWithTag("lyrics-editor-cancel"),
            "lyrics-editor-content").performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithTag("lyrics-editor-layout").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun selectPausedLyricsEditorTab(tab: Int) {
        val target = composeRule.onNodeWithTag("lyrics-editor-tab-$tab")
        scrollPausedEditorIntoView(target, "lyrics-editor-content").performClick()
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            target.fetchSemanticsNode().config[SemanticsProperties.Selected]
        }
    }

    private fun scrollPausedEditorIntoView(
        target: SemanticsNodeInteraction,
        scrollTag: String
    ): SemanticsNodeInteraction {
        val scroller = composeRule.onNodeWithTag(scrollTag)
        repeat(20) {
            val targetNode = target.fetchSemanticsNode()
            val targetBounds = Rect(targetNode.positionInRoot, targetNode.size.toSize())
            val scrollNode = scroller.fetchSemanticsNode()
            val viewport = scrollNode.boundsInRoot
            val delta = when {
                targetBounds.top <= viewport.top && targetBounds.bottom >= viewport.bottom -> 0f
                targetBounds.top < viewport.top -> targetBounds.top - viewport.top
                targetBounds.bottom > viewport.bottom -> targetBounds.bottom - viewport.bottom
                else -> 0f
            }
            if (kotlin.math.abs(delta) <= 1f) return target.assertIsDisplayed()
            check(scrollNode.config.contains(SemanticsActions.ScrollBy)) {
                "目标未完整可见且编辑容器无法滚动: $scrollTag, target=$targetBounds, viewport=$viewport"
            }
            scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, delta) }
            composeRule.mainClock.advanceTimeBy(500L)
            composeRule.waitForIdle()
        }
        error("编辑操作在限定滚动次数内仍不可达: $scrollTag")
    }

    private fun verifyPhonePortraitReturnAndReopening(
        scenario: ActivityScenario<MainActivity>,
        context: Context,
        songName: String
    ) {
        assertRequestedOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
        waitForSingleBackButton()
        composeRule.onNodeWithTag("nowPlayingBackButton").performClick()
        waitForPhonePortrait(scenario, songName)
        composeRule.onNodeWithContentDescription(songName, useUnmergedTree = true).assertIsDisplayed()
        assertPlaybackInformationVisible(true)
        capture(context, "portrait-player")

        scenario.recreate()
        waitForPhonePortrait(scenario, songName)
        composeRule.onNodeWithContentDescription(songName, useUnmergedTree = true).assertIsDisplayed()
        assertRequestedOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        assertPlaybackInformationVisible(true)

        waitForSingleBackButton()
        composeRule.onNodeWithTag("nowPlayingBackButton").performClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithText(songName).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithTag("nowPlayingWideLayout", useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText("lyrics").assertIsDisplayed()
        assertRequestedOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
        composeRule.onNodeWithText(songName, useUnmergedTree = true).performNativeClick()
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithTag("nowPlayingWideLayout", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertRequestedOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_FULL_USER)
        waitForLandscapeCoverReady(songName)
        assertPlaybackInformationVisible(false, "nowPlayingLandscapeCoverPage")
        capture(context, "player-reopened")
    }

    private fun verifyTabletPortraitAndLandscape(
        scenario: ActivityScenario<MainActivity>,
        context: Context,
        songName: String,
        playLabel: String
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
        try {
            waitForNativeOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            waitForTabletPortrait(songName)
            val layout = composeRule.onNodeWithTag("nowPlayingTabletPortraitLayout", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val cover = composeRule.onNodeWithContentDescription(songName, useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val density = context.resources.displayMetrics.density
            assertTrue("平板竖屏封面应限制尺寸", cover.width <= 380f * density + 1f)
            assertTrue("平板竖屏封面应保持方形", kotlin.math.abs(cover.width - cover.height) <= 1f)
            assertTrue("封面应位于平板竖屏布局内", layout.contains(cover.topLeft) &&
                layout.contains(cover.bottomRight - Offset(1f, 1f)))
            composeRule.onNodeWithContentDescription(playLabel, useUnmergedTree = true).assertIsDisplayed()
            composeRule.onAllNodesWithTag("appNavigationRail").assertCountEquals(0)
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                val lines = composeRule.onAllNodesWithText(LYRIC_LINE, useUnmergedTree = true)
                lines.fetchSemanticsNodes().indices.any { lines[it].isDisplayed() }
            }
            assertPlaybackInformationVisible(true)
            capture(context, "tablet-portrait-player")
            val localized = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))
        composeRule.onNodeWithTag("nowPlayingLyricsAction")
                .assertContentDescriptionEquals(localized.getString(CoreCommonR.string.lyrics_title)).performClick()
            composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
                composeRule.mainClock.advanceTimeBy(64L)
                composeRule.onAllNodesWithTag("lyricsScreen", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithTag("nowPlayingTabletPortraitLayout", useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty()
            }
            composeRule.onNodeWithTag("lyricsScreen", useUnmergedTree = true).assertIsDisplayed()
            composeRule.onNodeWithText(localized.getString(CoreCommonR.string.lyrics_adjust_behavior)).assertDoesNotExist()
            Espresso.pressBack()
            waitForTabletPortrait(songName)
            scenario.recreate()
            waitForNativeOrientation(scenario, Configuration.ORIENTATION_PORTRAIT)
            waitForTabletPortrait(songName)
            assertRequestedOrientation(scenario, ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
            composeRule.onNodeWithContentDescription(playLabel, useUnmergedTree = true).assertIsDisplayed()
            assertPlaybackInformationVisible(true)
            capture(context, "tablet-portrait-player-recreated")
        } finally {
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
        }
        waitForNativeOrientation(scenario, Configuration.ORIENTATION_LANDSCAPE)
        waitForLandscapeCoverReady(songName)
        composeRule.onNodeWithTag("nowPlayingWideLayout", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onAllNodesWithTag("nowPlayingTabletPortraitLayout", useUnmergedTree = true)
            .assertCountEquals(0)
        assertPlaybackInformationVisible(true, "nowPlayingLandscapeCoverPage")
        capture(context, "tablet-landscape-restored")
    }

    private fun waitForNativeOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int) {
        var frameView: View? = null
        var frameObserved = false
        // 先等新窗口绘制一帧，避免 Compose 把旋转前已销毁的窗口当作同步目标
        composeRule.waitUntil(STARTUP_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            var ready = false
            if (scenario.state == Lifecycle.State.RESUMED) {
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val landscape = orientation == Configuration.ORIENTATION_LANDSCAPE
                    if (activity.resources.configuration.orientation == orientation &&
                        decor.isAttachedToWindow && decor.hasWindowFocus() &&
                        decor.width > 0 && decor.height > 0 &&
                        (decor.width > decor.height) == landscape
                    ) {
                        if (frameView !== decor) {
                            frameView = decor
                            frameObserved = false
                            decor.postOnAnimation { if (frameView === decor) frameObserved = true }
                        }
                        ready = frameObserved
                    } else {
                        frameView = null
                        frameObserved = false
                    }
                }
            }
            ready
        }
    }

    private fun waitForTabletPortrait(songName: String) {
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            val layouts = composeRule.onAllNodesWithTag("nowPlayingTabletPortraitLayout", useUnmergedTree = true)
            val covers = composeRule.onAllNodesWithContentDescription(songName, useUnmergedTree = true)
            layouts.fetchSemanticsNodes().size == 1 && layouts[0].isDisplayed() &&
                covers.fetchSemanticsNodes().size == 1 && covers[0].isDisplayed()
        }
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitForIdle()
    }

    private fun waitForLandscapeCoverReady(songName: String) {
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            val titles = composeRule.onAllNodes(
                hasText(songName) and hasAnyAncestor(hasTestTag("nowPlayingLandscapeCoverPage")),
                useUnmergedTree = true
            )
            titles.fetchSemanticsNodes().indices.any { titles[it].isDisplayed() }
        }
        val footers = composeRule.onAllNodesWithTag("nowPlayingBottomControls", useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (footers.isNotEmpty()) {
            val wide = composeRule.onNodeWithTag("nowPlayingWideLayout", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue("横屏底部控制区应完整落在播放布局中", footers.single().boundsInRoot.bottom <= wide.bottom)
        }
    }

    private fun waitForSingleBackButton() {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            composeRule.mainClock.advanceTimeBy(64L)
            composeRule.onAllNodesWithTag("nowPlayingBackButton")
                .fetchSemanticsNodes().size == 1
        }
        composeRule.onNodeWithTag("nowPlayingBackButton").assertIsDisplayed()
    }

    private fun waitForPhonePortrait(scenario: ActivityScenario<MainActivity>, songName: String? = null) {
        composeRule.mainClock.advanceTimeBy(1_000L)
        composeRule.waitUntil(PLAYER_TIMEOUT_MS) {
            if (!composeRule.mainClock.autoAdvance) composeRule.mainClock.advanceTimeBy(64L)
            var portrait = false
            scenario.onActivity { activity ->
                portrait = activity.requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT &&
                    activity.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
            }
            val contentReady = songName?.let {
                val covers = composeRule.onAllNodesWithContentDescription(it, useUnmergedTree = true)
                covers.fetchSemanticsNodes().size == 1 && covers[0].isDisplayed()
            } ?: true
            portrait && composeRule.onAllNodesWithTag("nowPlayingWideLayout", useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty() && contentReady
        }
    }

    private fun assertRequestedOrientation(scenario: ActivityScenario<MainActivity>, expected: Int) {
        scenario.onActivity { activity -> assertTrue(activity.requestedOrientation == expected) }
    }

    private fun nativeSelectionMenuShown(labels: Set<String>): Boolean {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) automation.clearCache()
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        automation.windows.mapNotNull { it.root }.forEach(pending::add)
        var visited = 0
        while (pending.isNotEmpty() && visited++ < 2_000) {
            val node = pending.removeFirst()
            val resourceId = node.viewIdResourceName.orEmpty()
            if (node.isVisibleToUser && (resourceId.contains("floating_toolbar") ||
                    (resourceId.startsWith("android:") && node.text?.toString() in labels))) return true
            repeat(node.childCount) { index -> node.getChild(index)?.let(pending::add) }
        }
        return false
    }

    private fun createLocalSong(context: Context, directory: File): SongItem {
        val audio = File(directory, "landscape.wav")
        val pcm = ByteArray(8_000 * 60 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(1)
            putInt(8_000)
            putInt(16_000)
            putShort(2)
            putShort(16)
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }.array()
        audio.outputStream().use {
            it.write(header)
            it.write(pcm)
        }
        val lyric = (0..11).joinToString("\n") { index ->
            "[00:${(index * 4).toString().padStart(2, '0')}.00]" +
                if (index == 3) LYRIC_LINE else "Local landscape lyric ${index + 1}"
        }
        File(directory, "${audio.nameWithoutExtension}.lrc").writeText(lyric, Charsets.UTF_8)
        val translation = lyric.replace("Local landscape lyric", "本地译文").replace(LYRIC_LINE, "为音乐留多一点空间")
        val phonetic = lyric.replace("Local landscape lyric", "Synthetic phonetic").replace(LYRIC_LINE, "Synthetic phonetic line")
        File(directory, "${audio.nameWithoutExtension}_trans.lrc").writeText(translation, Charsets.UTF_8)
        File(directory, "${audio.nameWithoutExtension}_roma.lrc").writeText(phonetic, Charsets.UTF_8)
        val cover = File(directory, "${audio.nameWithoutExtension}.png")
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.rgb(24, 40, 66))
            checkNotNull(context.getDrawable(R.drawable.ic_launcher_foreground_material)).apply {
                setBounds(0, 0, bitmap.width, bitmap.height)
                draw(canvas)
            }
            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        // 真实本地探测会重写标题和歌词，先从侧车生成与播放补全一致的元数据
        return LocalMediaSupport.toSongItem(LocalMediaSupport.inspect(context, Uri.fromFile(audio))).also {
            check(it.localFilePath == audio.absolutePath && it.mediaUri == audio.absolutePath)
            check(it.matchedLyric == lyric) { "真实本地探测未读取合成 LRC 侧车" }
            check(it.matchedTranslatedLyric == translation && it.matchedRomanizedLyric == phonetic) {
                "真实本地探测未读取合成翻译及音译侧车"
            }
            check(!it.coverUrl.isNullOrBlank()) { "真实本地探测未读取合成封面" }
        }
    }

    private fun capture(context: Context, stage: String) {
        val prefix = InstrumentationRegistry.getArguments().getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf { it.isNotBlank() } ?: return
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val file = File(checkNotNull(context.getExternalFilesDir(null)), "$prefix-$stage.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("LandscapeFlowTest", "screenshot=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private data class SavedSettings(
        val disclaimer: Boolean,
        val onboarding: Boolean,
        val startDestination: String,
        val densityScale: Float,
        val advancedLyrics: Boolean,
        val showLyricTranslation: Boolean,
        val lyricTranslationUsePhonetic: Boolean,
        val dynamicColor: Boolean,
        val dynamicBackground: Boolean,
        val coverBlur: Boolean,
        val coverLyrics: Boolean,
        val progressQualitySwitch: Boolean,
        val progressAudioCodec: Boolean,
        val progressAudioSpec: Boolean
    ) {
        suspend fun restore(settings: SettingsRepository, autoSettings: AutoSettingsRepository) {
            settings.setDisclaimerAccepted(disclaimer)
            settings.setStartupOnboardingCompleted(onboarding)
            settings.setDefaultStartDestination(startDestination)
            settings.setUiDensityScale(densityScale)
            settings.setAdvancedLyricsEnabled(advancedLyrics)
            settings.setShowLyricTranslation(showLyricTranslation)
            settings.setLyricTranslationUsePhonetic(lyricTranslationUsePhonetic)
            settings.setDynamicColor(dynamicColor)
            settings.setNowPlayingDynamicBackgroundEnabled(dynamicBackground)
            settings.setNowPlayingCoverBlurBackgroundEnabled(coverBlur)
            autoSettings.setNowPlayingCoverLyricsEnabled(coverLyrics)
            autoSettings.setNowPlayingProgressShowQualitySwitch(progressQualitySwitch)
            autoSettings.setNowPlayingProgressShowAudioCodec(progressAudioCodec)
            autoSettings.setNowPlayingProgressShowAudioSpec(progressAudioSpec)
        }

        companion object {
            suspend fun read(settings: SettingsRepository) = SavedSettings(
                settings.disclaimerAcceptedFlow.filterNotNull().first(),
                settings.startupOnboardingCompletedFlow.filterNotNull().first(),
                settings.defaultStartDestinationFlow.first(), settings.uiDensityScaleFlow.first(),
                settings.advancedLyricsEnabledFlow.first(), settings.showLyricTranslationFlow.first(),
                settings.lyricTranslationUsePhoneticFlow.first(), settings.dynamicColorFlow.first(),
                settings.nowPlayingDynamicBackgroundEnabledFlow.first(),
                settings.nowPlayingCoverBlurBackgroundEnabledFlow.first(),
                settings.nowPlayingCoverLyricsEnabledFlow.first(),
                settings.nowPlayingProgressShowQualitySwitchFlow.first(),
                settings.nowPlayingProgressShowAudioCodecFlow.first(),
                settings.nowPlayingProgressShowAudioSpecFlow.first()
            )
        }
    }

    private companion object {
        const val STARTUP_TIMEOUT_MS = 30_000L
        const val PLAYER_TIMEOUT_MS = 15_000L
        const val LYRIC_LINE = "A little more room for the music"
    }
}
