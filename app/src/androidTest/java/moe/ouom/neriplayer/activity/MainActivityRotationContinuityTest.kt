package moe.ouom.neriplayer.activity

import android.app.Application
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.player.service.AudioPlayerService
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.MediaBrowserIsolationRule
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.settingsPageTransitionSceneTag
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class MainActivityRotationContinuityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun cleanPlayerRotationPreservesSettingsDraftScrollAndBackDepth() =
        verifyRotationContinuity(UiStyle.Clean)

    @Test
    fun materialPlayerRotationPreservesSettingsDraftScrollAndBackDepth() =
        verifyRotationContinuity(UiStyle.Material)

    private fun verifyRotationContinuity(style: UiStyle) {
        fun logPhase(phase: String) {
            Log.i("RotationContinuityTest", "style=${style.name} phase=$phase")
        }
        assumeComposeHostAvailable()
        assumeTrue("需要 API 30+ 手机模拟器", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish") &&
            context.resources.configuration.smallestScreenWidthDp < 600)
        assertFalse("测试开始前不能有残留的播放服务", AudioPlayerService.isInstanceActiveForDiagnostics())
        val settings = SettingsRepository(context)
        val savedSettings = runBlocking { context.dataStore.data.first() }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val savedCaches = CacheNames.map { SavedCache.read(context, it) }
        val savedQueue = runBlocking { SavedQueue.read(context) }
        val savedMotion = NeriMotionStyle.clean
        val automation = instrumentation.uiAutomation
        val savedRotation = Settings.System.getInt(context.contentResolver,
            Settings.System.USER_ROTATION, UiAutomation.ROTATION_FREEZE_0)
            .coerceIn(UiAutomation.ROTATION_FREEZE_0, UiAutomation.ROTATION_FREEZE_270)
        val savedAutoRotation = Settings.System.getInt(context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 1) != 0
        val directory = File(context.cacheDir, "rotation-continuity-${System.nanoTime()}")
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            logPhase("prepare")
            check(directory.mkdirs())
            val song = createPausedSong(directory)
            instrumentation.runOnMainSync { PlayerManager.release() }
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                PreviewThemePromptRepository(context).answer(enable = false)
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setDefaultStartDestination(Destinations.Settings.route)
                settings.setUiStyle(style)
                settings.setUiDensityScale(1f)
                settings.setKeepLastPlaybackProgress(true)
                settings.setPreemptAudioFocus(true)
                settings.setAllowMixedPlayback(false)
                settings.setNowPlayingDynamicBackgroundEnabled(false)
                settings.setNowPlayingCoverBlurBackgroundEnabled(false)
                settings.setNowPlayingImmersiveMode(false)
                // 暂停恢复避免播放统计影响方向回归
                QueueFiles.forEach { name ->
                    val file = File(context.filesDir, name)
                    check(!file.exists() || file.delete())
                }
                queueStore().replaceSnapshot(PersistedState(listOf(song), index = 0,
                    positionMs = 1_000L, shouldResumePlayback = false))
            }
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
            instrumentation.runOnMainSync { PlayerManager.initialize(context.applicationContext as Application) }
            logPhase("activity-launch")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activeScenario = checkNotNull(scenario)
            UiFailureDiagnostics.onFailure("rotation-continuity-${style.name}") {
                awaitOrientation(activeScenario, Configuration.ORIENTATION_PORTRAIT)
                awaitUi {
                    composeRule.onNode(hasSetTextAction()).isDisplayed() &&
                        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).isDisplayed() &&
                        PlayerManager.currentSongFlow.value?.mediaUri == song.mediaUri &&
                        AudioPlayerService.isInstanceActiveForDiagnostics()
                }
                logPhase("home-ready")
                val search = composeRule.onNode(hasSetTextAction())
                // 草稿是旋转前的夹具，直接更新真实输入状态以隔离系统键盘
                search.performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString(SearchDraft)))
                }
                search.assertTextEquals(SearchDraft)
                logPhase("draft-ready")
                val lyricsResult = lyricsPageSearchResult()
                awaitUi { lyricsResult.isDisplayed() }
                lyricsResult.performClick()
                awaitDetail()
                val detail = detailList()
                detail.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 450f) }
                composeRule.waitForIdle()
                val savedScroll = scrollPosition(detail)
                assertTrue("歌词设置详情必须已滚动，才能验证旋转后位置连续", savedScroll > 0f)
                logPhase("detail-ready")
                lateinit var originalActivity: MainActivity
                activeScenario.onActivity { originalActivity = it }

                openPlayer(activeScenario)
                closePlayerToDetail(activeScenario, originalActivity, savedScroll)
                logPhase("baseline-player-roundtrip")
                openPlayer(activeScenario)
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                awaitPlayerOrientation(activeScenario, Configuration.ORIENTATION_LANDSCAPE)
                assertSameActivity(activeScenario, originalActivity)
                logPhase("landscape")

                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_0))
                awaitPlayerOrientation(activeScenario, Configuration.ORIENTATION_PORTRAIT)
                assertSameActivity(activeScenario, originalActivity)
                logPhase("portrait")
                assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_90))
                awaitPlayerOrientation(activeScenario, Configuration.ORIENTATION_LANDSCAPE)
                composeRule.onNodeWithTag(PlayerBackTag).performClick()
                awaitPlayerOrientation(activeScenario, Configuration.ORIENTATION_PORTRAIT)
                assertSameActivity(activeScenario, originalActivity)
                logPhase("top-back-portrait")
                activeScenario.onActivity { activity ->
                    assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
                }

                closePlayerToDetail(activeScenario, originalActivity, savedScroll)

                Espresso.pressBack()
                awaitUi {
                    composeRule.onNodeWithTag(HomeTag, useUnmergedTree = true).isDisplayed() &&
                        composeRule.onAllNodesWithTag(DetailTag, useUnmergedTree = true)
                            .fetchSemanticsNodes().isEmpty()
                }
                composeRule.onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(HomeTag)),
                    useUnmergedTree = true).performScrollToIndex(0)
                search.assertIsDisplayed()
                search.assertTextEquals(SearchDraft)
                composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()
                assertSameActivity(activeScenario, originalActivity)
                logPhase("complete")
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { scenario?.close() }
            cleanup {
                instrumentation.runOnMainSync {
                    context.stopService(Intent(context, AudioPlayerService::class.java))
                }
                composeRule.waitUntil(UiTimeoutMs) { !AudioPlayerService.isInstanceActiveForDiagnostics() }
            }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { runBlocking { context.dataStore.updateData { savedSettings } } }
            cleanup { runBlocking { context.debugBuildWarningDataStore.updateData { savedWarning } } }
            savedCaches.forEach { saved -> cleanup { saved.restore(context) } }
            cleanup {
                assertEquals(savedSettings, runBlocking { context.dataStore.data.first() })
                assertEquals(savedWarning, runBlocking { context.debugBuildWarningDataStore.data.first() })
            }
            cleanup { assertTrue(automation.setRotation(savedRotation)) }
            if (savedAutoRotation) cleanup { assertTrue(automation.setRotation(UiAutomation.ROTATION_UNFREEZE)) }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            NeriMotionStyle.clean = savedMotion
            if (failure != null) cleanupFailures.forEach(failure::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun lyricsPageSearchResult(): SemanticsNodeInteraction {
        val localized = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))
        val title = localized.getString(SettingsPage.Lyrics.titleRes)
        return composeRule.onNode(hasClickAction() and SemanticsMatcher("歌词设置页搜索结果") { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == listOf(title, title)
        })
    }

    private fun openPlayer(scenario: ActivityScenario<MainActivity>) {
        awaitUi { composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).isDisplayed() }
        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).performNativeClick()
        awaitUi { composeRule.onNodeWithTag(PlayerBackTag).isDisplayed() }
        scenario.onActivity { activity ->
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_FULL_USER, activity.requestedOrientation)
        }
    }

    private fun closePlayerToDetail(
        scenario: ActivityScenario<MainActivity>, expected: MainActivity, savedScroll: Float
    ) {
        Espresso.pressBack()
        awaitUi {
            composeRule.onAllNodesWithTag(PlayerBackTag).fetchSemanticsNodes().isEmpty() &&
                composeRule.onNodeWithTag(DetailTag, useUnmergedTree = true).isDisplayed()
        }
        assertSameActivity(scenario, expected)
        assertEquals("关闭播放器后应保留原详情滚动位置", savedScroll,
            scrollPosition(detailList()), 0.01f)
        assertFalse("旋转和导航不能意外开始播放", PlayerManager.isPlayingFlow.value)
    }

    private fun awaitPlayerOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int) {
        awaitOrientation(scenario, orientation)
        awaitUi {
            val wide = composeRule.onAllNodesWithTag(WidePlayerTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
            composeRule.onNodeWithTag(PlayerBackTag).isDisplayed() &&
                if (orientation == Configuration.ORIENTATION_LANDSCAPE) wide.size == 1 else wide.isEmpty()
        }
    }

    private fun awaitOrientation(scenario: ActivityScenario<MainActivity>, orientation: Int) = awaitUi {
        var ready = false
        if (scenario.state == Lifecycle.State.RESUMED) scenario.onActivity { activity ->
            val configuration = activity.resources.configuration
            ready = activity.hasWindowFocus() && configuration.orientation == orientation &&
                (configuration.screenWidthDp > configuration.screenHeightDp) ==
                (orientation == Configuration.ORIENTATION_LANDSCAPE)
        }
        ready
    }

    private fun assertSameActivity(scenario: ActivityScenario<MainActivity>, expected: MainActivity) {
        scenario.onActivity { activity ->
            assertSame("播放器旋转应保留当前 Activity 和底层页面状态", expected, activity)
        }
    }

    private fun awaitDetail() = awaitUi {
        composeRule.onNodeWithTag(DetailTag, useUnmergedTree = true).isDisplayed()
    }

    private fun detailList() = composeRule.onNode(hasScrollToIndexAction() and
        hasAnyAncestor(hasTestTag(DetailTag)), useUnmergedTree = true)

    private fun scrollPosition(list: SemanticsNodeInteraction): Float =
        list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()

    private fun awaitUi(condition: () -> Boolean) {
        composeRule.waitUntil(UiTimeoutMs) {
            dismissImmersiveConfirmation()
            condition()
        }
    }

    private fun dismissImmersiveConfirmation() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.packageName?.toString() != "android") return
        if (root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title")
                .none { it.isVisibleToUser }) return
        root.findAccessibilityNodeInfosByViewId("android:id/ok")
            .singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun createPausedSong(directory: File): PersistedSongItem {
        val audio = File(directory, "rotation.wav")
        val pcm = ByteArray(8_000 * 10 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        val cover = File(directory, "cover.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(45, 94, 140))
            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return PersistedSongItem(id = -System.nanoTime(), name = "Rotation continuity fixture",
            artist = "Offline fixture", album = "Test", albumId = 0, durationMs = 10_000L,
            coverUrl = cover.absolutePath, mediaUri = audio.absolutePath,
            localFilePath = audio.absolutePath, localFileName = audio.name,
            matchedLyric = "[00:00.00]Rotation fixture\n[00:03.00]Second fixture line")
    }

    private fun queueStore() = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))

    private data class SavedQueue(
        val roomPrimary: Boolean,
        val state: PersistedState?,
        val legacyFiles: Map<String, ByteArray?>
    ) {
        suspend fun restore(context: Context) {
            val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
            if (roomPrimary && state != null) store.replaceSnapshot(state) else store.clear()
            legacyFiles.forEach { (name, bytes) ->
                val file = File(context.filesDir, name)
                if (bytes != null) file.writeBytes(bytes) else check(!file.exists() || file.delete())
            }
            if (!roomPrimary) store.markLegacyJsonPrimary(System.currentTimeMillis())
        }

        companion object {
            suspend fun read(context: Context): SavedQueue {
                val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
                return SavedQueue(store.isRoomPrimary(), store.readIfRoomPrimary(),
                    QueueFiles.associateWith { File(context.filesDir, it).takeIf(File::exists)?.readBytes() })
            }
        }
    }

    private data class SavedCache(val name: String, val values: Map<String, Any>) {
        fun restore(context: Context) {
            val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val editor = preferences.edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, stringSet(value))
                    else -> error("不支持的同步缓存类型: cache=$name, key=$key")
                }
            }
            assertTrue("应恢复同步缓存: $name", editor.commit())
            assertEquals(values, copyValues(preferences))
        }

        companion object {
            fun read(context: Context, name: String) = SavedCache(name,
                copyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE)))

            private fun copyValues(preferences: SharedPreferences): Map<String, Any> =
                preferences.all.mapValues { (key, value) ->
                    when (value) {
                        is Boolean -> value
                        is String -> value
                        is Int -> value
                        is Long -> value
                        is Float -> value
                        is Set<*> -> stringSet(value)
                        else -> error("不支持的同步缓存类型: key=$key")
                    }
                }

            private fun stringSet(value: Set<*>): Set<String> = value.map { entry ->
                check(entry is String) { "同步缓存集合必须只包含字符串" }
                entry
            }.toSet()
        }
    }

    private companion object {
        @ClassRule
        @JvmField
        val mediaBrowserIsolation: org.junit.rules.TestRule = MediaBrowserIsolationRule()

        const val UiTimeoutMs = 30_000L
        const val SearchDraft = "lyrics"
        const val MiniPlayerTag = "appMiniPlayer"
        const val PlayerBackTag = "nowPlayingBackButton"
        const val WidePlayerTag = "nowPlayingWideLayout"
        val DetailTag = settingsPageTransitionSceneTag(SettingsPage.Lyrics)
        val HomeTag = settingsPageTransitionSceneTag(null)
        val QueueFiles = listOf("last_playlist.json", "last_playback_state.json")
        val CacheNames = listOf("startup_progress_cache", "theme_snapshot_cache", "playback_snapshot_cache")
    }
}
