package moe.ouom.neriplayer.ui

import android.app.Application
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.media.displayName
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.settings.generated.AutoSettingsRepository
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.settingsPageTransitionSceneTag
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(AndroidJUnit4::class)
class MainActivityTabletLyricsEdgeBackTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test
    fun cleanPreviewEnabledFirstEdgeReturnsToTheOriginalSettingsDetail() = verifyEdgeBack(UiStyle.Clean, true)

    @Test
    fun cleanPreviewDisabledFirstEdgeReturnsToTheOriginalSettingsDetail() = verifyEdgeBack(UiStyle.Clean, false)

    @Test
    fun materialPreviewEnabledFirstEdgeReturnsToTheOriginalSettingsDetail() = verifyEdgeBack(UiStyle.Material, true)

    @Test
    fun materialPreviewDisabledFirstEdgeReturnsToTheOriginalSettingsDetail() = verifyEdgeBack(UiStyle.Material, false)

    private fun verifyEdgeBack(style: UiStyle, preview: Boolean) {
        assumeTrue("此回归仅允许操作 API 35+ 模拟器", Build.VERSION.SDK_INT >= 35 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeTrue("需要真实平板配置", context.resources.configuration.smallestScreenWidthDp >= 600)
        assumeTrue("需要系统手势导航",
            Settings.Secure.getInt(context.contentResolver, "navigation_mode", -1) == 2)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        assertTrue("模拟器屏幕必须已点亮", power.isInteractive)
        assertFalse("模拟器必须已解锁", keyguard.isDeviceLocked || keyguard.isKeyguardLocked)

        val savedSettings = runBlocking { context.dataStore.data.first() }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val savedCaches = CacheNames.map { SavedCache.read(context, it) }
        val savedQueue = runBlocking { SavedQueue.read(context) }
        val savedMotion = NeriMotionStyle.clean
        val savedRotation = Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
            .coerceIn(UiAutomation.ROTATION_FREEZE_0, UiAutomation.ROTATION_FREEZE_270)
        val savedAutoRotation = Settings.System.getInt(context.contentResolver,
            Settings.System.ACCELEROMETER_ROTATION, 1) != 0
        val directory = File(context.cacheDir, "tablet-lyrics-edge-fixture-${System.nanoTime()}")
        var scenario: ActivityScenario<MainActivity>? = null
        var failure: Throwable? = null
        try {
            check(directory.mkdirs())
            val song = createPausedSong(directory)
            instrumentation.runOnMainSync { PlayerManager.release() }
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                PreviewThemePromptRepository(context).answer(enable = false)
                SettingsRepository(context).apply {
                    setDisclaimerAccepted(true)
                    setStartupOnboardingCompleted(true)
                    setDefaultStartDestination(Destinations.Settings.route)
                    setUiStyle(style)
                    setUiDensityScale(1f)
                    setKeepLastPlaybackProgress(true)
                    setNowPlayingImmersiveMode(false)
                    setNowPlayingDynamicBackgroundEnabled(false)
                    setNowPlayingCoverBlurBackgroundEnabled(false)
                    setAdvancedLyricsEnabled(true)
                }
                AutoSettingsRepository(context).setPredictiveBackPreviewEnabled(preview)
                QueueFiles.forEach { name -> File(context.filesDir, name).let { check(!it.exists() || it.delete()) } }
                queueStore().replaceSnapshot(PersistedState(listOf(song), index = 0,
                    positionMs = 1_000L, shouldResumePlayback = false))
            }
            assertTrue(automation.setRotation(UiAutomation.ROTATION_FREEZE_CURRENT))
            instrumentation.runOnMainSync { PlayerManager.initialize(context.applicationContext as Application) }
            scenario = ActivityScenario.launch(MainActivity::class.java)
            val activeScenario = checkNotNull(scenario)
            UiFailureDiagnostics.onFailure("tablet-lyrics-edge-${style.name}-$preview") {
                var targetRotation = UiAutomation.ROTATION_FREEZE_0
                activeScenario.onActivity { activity ->
                    val rotation = checkNotNull(activity.window.decorView.display).rotation
                    targetRotation = if (activity.resources.configuration.orientation ==
                        Configuration.ORIENTATION_LANDSCAPE) rotation else (rotation + 1) % 4
                }
                assertTrue(automation.setRotation(targetRotation))
                awaitUi {
                    var ready = false
                    activeScenario.onActivity { activity ->
                        val config = activity.resources.configuration
                        ready = activity.hasWindowFocus() && config.smallestScreenWidthDp >= 600 &&
                            config.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                            config.screenWidthDp > config.screenHeightDp
                    }
                    ready && composeRule.onNode(hasSetTextAction()).isDisplayed() &&
                        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).isDisplayed() &&
                        PlayerManager.currentSongFlow.value?.mediaUri == song.mediaUri
                }
                val search = composeRule.onNode(hasSetTextAction())
                search.performSemanticsAction(SemanticsActions.SetText) {
                    assertTrue(it(AnnotatedString(SearchDraft)))
                }
                val localized = LanguageManager.localizedContext(context, LanguageManager.getCurrentLanguage(context))
                val lyricsTitle = localized.getString(SettingsPage.Lyrics.titleRes)
                val result = composeRule.onNode(hasClickAction() and SemanticsMatcher("歌词设置搜索结果") { node ->
                    node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == listOf(lyricsTitle, lyricsTitle)
                })
                awaitUi { result.isDisplayed() }
                result.performClick()
                awaitUi { composeRule.onNodeWithTag(DetailTag, useUnmergedTree = true).isDisplayed() }
                val detail = composeRule.onNode(hasScrollToIndexAction() and
                    hasAnyAncestor(hasTestTag(DetailTag)), useUnmergedTree = true)
                detail.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 450f) }
                composeRule.waitForIdle()
                val originalDetail = detail.fetchSemanticsNode()
                val originalScroll = originalDetail.config[SemanticsProperties.VerticalScrollAxisRange].value()
                assertTrue("原设置详情应已滚动", originalScroll > 0f)
                lateinit var originalActivity: MainActivity
                activeScenario.onActivity { originalActivity = it }

                activeScenario.onActivity { PlayerManager.pause() }
                val miniTitle = composeRule.onNode(
                    hasAnyAncestor(hasTestTag(MiniPlayerTag)) and SemanticsMatcher("迷你播放器当前歌曲标题") { node ->
                        val currentSong = PlayerManager.currentSongFlow.value
                        currentSong != null && node.config.getOrNull(SemanticsProperties.Text)
                            ?.any { it.text == currentSong.displayName() } == true
                    },
                    useUnmergedTree = true
                )
                awaitUi {
                    !PlayerManager.isPlayingFlow.value && !PlayerManager.playbackControlPlayingFlow.value &&
                        miniTitle.isDisplayed()
                }
                miniTitle.performNativeClick()
                awaitUi {
                    val lines = composeRule.onAllNodesWithText(LyricLine, useUnmergedTree = true)
                    composeRule.onNodeWithTag(WidePlayerTag, useUnmergedTree = true).isDisplayed() &&
                        lines.fetchSemanticsNodes().indices.any { lines[it].isDisplayed() }
                }
                composeRule.onNodeWithTag("nowPlayingLyricPane", useUnmergedTree = true).assertIsDisplayed()
                awaitUi {
                    var ready = false
                    activeScenario.onActivity { activity ->
                        val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
                        ready = activity.hasWindowFocus() && insets != null &&
                            !insets.isVisible(WindowInsetsCompat.Type.navigationBars())
                    }
                    ready
                }
                val label = "${style.name}-preview-$preview"
                var width = 0
                var height = 0
                activeScenario.onActivity { activity ->
                    val config = activity.resources.configuration
                    assertTrue(config.smallestScreenWidthDp >= 600)
                    assertEquals(Configuration.ORIENTATION_LANDSCAPE, config.orientation)
                    width = activity.window.decorView.width
                    height = activity.window.decorView.height
                    Log.i(Tag, "$label before-first-edge sw=${config.smallestScreenWidthDp} " +
                        "width=$width height=$height navigationBarsHidden=true")
                }
                saveScreenshot("$label-before-first-edge")
                // 只发送一次真实右边缘手势，系统栏是否消费首滑属于本次断言范围
                ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(
                    "input swipe ${width - 1} ${height / 2} ${width / 2} ${height / 2} 350"
                )).use { it.readBytes() }
                awaitUi {
                    composeRule.onAllNodesWithTag(WidePlayerTag, useUnmergedTree = true)
                        .fetchSemanticsNodes().isEmpty() &&
                        composeRule.onNodeWithTag(DetailTag, useUnmergedTree = true).isDisplayed()
                }
                saveScreenshot("$label-after-first-edge")
                activeScenario.onActivity { assertSame("首滑应返回同一个 Activity", originalActivity, it) }
                assertEquals("首滑应返回原设置内容", originalDetail.id, detail.fetchSemanticsNode().id)
                assertEquals("首滑应保留设置滚动位置", originalScroll,
                    detail.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value(), 0.01f)
                search.assertTextEquals(SearchDraft)
                assertFalse("夹具不得实际开始播放", PlayerManager.isPlayingFlow.value)
                Log.i(Tag, "$label first-edge-returned-to-original-detail")
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
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            cleanup { runBlocking { savedQueue.restore(context) } }
            cleanup { runBlocking { context.dataStore.updateData { savedSettings } } }
            cleanup { runBlocking { context.debugBuildWarningDataStore.updateData { savedWarning } } }
            savedCaches.forEach { saved -> cleanup { saved.restore(context) } }
            cleanup { assertEquals(savedSettings, runBlocking { context.dataStore.data.first() }) }
            cleanup { assertEquals(savedWarning, runBlocking { context.debugBuildWarningDataStore.data.first() }) }
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

    private fun awaitUi(condition: () -> Boolean) {
        composeRule.waitUntil(UiTimeoutMs) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == "android" &&
                root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title").any { it.isVisibleToUser }) {
                root.findAccessibilityNodeInfosByViewId("android:id/ok")
                    .singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            condition()
        }
    }

    private fun saveScreenshot(label: String) {
        automation.waitForIdle(500L, 5_000L)
        val bitmap = checkNotNull(automation.takeScreenshot())
        try {
            val directory = File(context.cacheDir, "tablet-lyrics-edge-evidence")
            check(directory.isDirectory || directory.mkdirs())
            val file = File(directory, "$label-${System.nanoTime()}.png")
            file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i(Tag, "screenshot=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun createPausedSong(directory: File): PersistedSongItem {
        val audio = File(directory, "edge-back.wav")
        val pcm = ByteArray(8_000 * 10 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray(Charsets.US_ASCII)).putInt(36 + pcm.size)
            .put("WAVEfmt ".toByteArray(Charsets.US_ASCII)).putInt(16).putShort(1).putShort(1)
            .putInt(8_000).putInt(16_000).putShort(2).putShort(16)
            .put("data".toByteArray(Charsets.US_ASCII)).putInt(pcm.size).array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        val lyric = "[00:00.00]$LyricLine\n[00:03.00]Second offline lyric line"
        File(directory, "edge-back.lrc").writeText(lyric)
        val cover = File(directory, "cover.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(45, 94, 140))
            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return PersistedSongItem(id = -System.nanoTime(), name = "Tablet edge back fixture",
            artist = "Offline fixture", album = "Test", albumId = 0, durationMs = 10_000L,
            coverUrl = cover.absolutePath, mediaUri = audio.absolutePath,
            localFilePath = audio.absolutePath, localFileName = audio.name, matchedLyric = lyric)
    }

    private fun queueStore() = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))

    private data class SavedQueue(
        val roomPrimary: Boolean,
        val state: PersistedState?,
        val legacy: Map<String, ByteArray?>
    ) {
        suspend fun restore(context: Context) {
            val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
            if (roomPrimary && state != null) store.replaceSnapshot(state) else store.clear()
            legacy.forEach { (name, bytes) ->
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

    private data class SavedCache(val name: String, val values: Map<String, *>) {
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
                    is Set<*> -> editor.putStringSet(key, value.map { check(it is String); it }.toSet())
                    else -> error("不支持的缓存类型: cache=$name, key=$key")
                }
            }
            assertTrue("应恢复启动缓存: $name", editor.commit())
            assertEquals(values, preferences.all)
        }

        companion object {
            fun read(context: Context, name: String) = SavedCache(name,
                context.getSharedPreferences(name, Context.MODE_PRIVATE).all.toMap())
        }
    }

    private companion object {
        const val Tag = "TabletLyricsEdgeBack"
        const val UiTimeoutMs = 30_000L
        const val SearchDraft = "lyrics"
        const val LyricLine = "Tablet edge back offline lyric"
        const val MiniPlayerTag = "appMiniPlayer"
        const val WidePlayerTag = "nowPlayingWideLayout"
        val DetailTag = settingsPageTransitionSceneTag(SettingsPage.Lyrics)
        val QueueFiles = listOf("last_playlist.json", "last_playback_state.json")
        val CacheNames = listOf("startup_progress_cache", "theme_snapshot_cache", "playback_snapshot_cache")
    }
}
