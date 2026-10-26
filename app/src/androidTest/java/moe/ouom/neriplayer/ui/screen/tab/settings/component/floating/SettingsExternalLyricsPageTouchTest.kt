package moe.ouom.neriplayer.ui.screen.tab.settings.component.floating

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewTreeObserver
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.persistence.PlaybackQueueRoomStore
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.model.playback.PersistedSongItem
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.lyrics.FloatingLyricsPreferences
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.EmulatorOfflineNetworkFixture
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.settingsPageTransitionSceneTag
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsPageRowTestTag
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsExternalLyricsPageTouchTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository by lazy { SettingsRepository(context) }
    private val labels = mutableMapOf<Int, String>()
    private lateinit var initialPreferences: FloatingLyricsPreferences
    private lateinit var expectedPreferences: FloatingLyricsPreferences
    private lateinit var activeScenario: ActivityScenario<MainActivity>
    private var fixtureDensityScale = 1f
    private var touchSequence = 0
    private val capturePrefix get() = InstrumentationRegistry.getArguments().getString("capturePrefix")

    @Test
    fun cleanExternalLyricsPageBottomRowsReceiveNativeTouchesWithMiniPlayer() {
        assertPageTouches(uiDensityScale = 1f)
    }

    @Test
    fun cleanExternalLyricsPageBottomRowsReceiveNativeTouchesAtExpandedUiDensity() {
        assertPageTouches(uiDensityScale = 1.2f)
    }

    @Test
    fun cleanExternalLyricsPageAllControlsPersistNativeTouchesAtExpandedUiDensity() {
        assertPageTouches(uiDensityScale = 1.2f, allControls = true)
    }

    private fun assertPageTouches(uiDensityScale: Float, allControls: Boolean = false) {
        fixtureDensityScale = uiDensityScale
        assumeComposeHostAvailable()
        assumeTrue("仅在 API 30+ 手机模拟器运行真实宿主触摸回归", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish") &&
            context.resources.configuration.smallestScreenWidthDp < 600)
        val originalOverlayMode = if (allControls) readSupportedPackageOverlayMode() else null
        val originalCaches = CachePreferenceNames.map { SavedSharedPreferences.read(context, it) }
        val originalCleanMotion = NeriMotionStyle.clean
        val originalSettings = runBlocking { context.dataStore.data.first() }
        val originalWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val originalQueue = runBlocking { SavedPlaybackQueue.read(context) }
        val directory = File(context.cacheDir, "external-lyrics-touch-${System.nanoTime()}")
        var scenario: ActivityScenario<MainActivity>? = null
        var networkFixture: EmulatorOfflineNetworkFixture? = null
        var failure: Throwable? = null
        try {
            networkFixture = EmulatorOfflineNetworkFixture.start(context)
            if (allControls) {
                setOverlayMode(AppOpsManager.MODE_ALLOWED)
                assertTrue("全控件测试不得跳转系统授权页", Settings.canDrawOverlays(context))
            }
            check(directory.mkdirs())
            val song = createPausedSong(directory)
            instrumentation.runOnMainSync { PlayerManager.release() }
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                context.dataStore.edit { it[booleanPreferencesKey("preview_theme_prompt_answered")] = true }
                repository.setDisclaimerAccepted(true)
                repository.setStartupOnboardingCompleted(true)
                repository.setDefaultStartDestination(Destinations.Settings.route)
                repository.setUiStyle(UiStyle.Clean)
                repository.setUiDensityScale(uiDensityScale)
                repository.setKeepLastPlaybackProgress(true)
                repository.setFloatingLyricsPreferences(
                    FloatingLyricsPreferences(enabled = false, hideInApp = allControls, showTranslation = false)
                )
                initialPreferences = repository.floatingLyricsPreferencesFlow.first()
                expectedPreferences = initialPreferences
                // 暂停队列只恢复迷你播放器，不启动播放或产生测试歌曲的播放统计
                for (name in QueueFiles) {
                    val file = File(context.filesDir, name)
                    check(!file.exists() || file.delete())
                }
                PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context)).replaceSnapshot(
                    PersistedState(listOf(song), index = 0, positionMs = 1_000L, shouldResumePlayback = false)
                )
            }
            logPhase("player-initialize-before")
            instrumentation.runOnMainSync { PlayerManager.initialize(context.applicationContext as Application) }
            logPhase("player-initialize-after")
            logPhase("activity-launch-before")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            activeScenario = scenario
            logPhase("activity-launch-after")
            scenario.onActivity { activity ->
                assertTrue("完整页面需要硬件渲染窗口", activity.window.decorView.isHardwareAccelerated)
                val localized = LanguageManager.localizedContext(activity, LanguageManager.getCurrentLanguage(activity))
                for (resource in ControlLabels) labels[resource] = localized.getString(resource)
            }
            composeRule.waitUntil(UiTimeoutMs) {
                PlayerManager.currentSongFlow.value?.mediaUri == song.mediaUri &&
                    composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).isDisplayed()
            }
            logPhase("mini-player-ready")
            assertMiniPlayer(song.name)
            val pageRow = composeRule.onNodeWithTag(settingsPageRowTestTag(SettingsPage.ExternalLyrics))
            swipeUntilVisible(pageRow, composeRule.onNode(hasScrollToIndexAction()), avoidMiniPlayer = true)
            logPhase("detail-route-click-before")
            pageRow.assertIsEnabled().performNativeClick()
            logPhase("detail-route-click-after")
            composeRule.waitUntil(UiTimeoutMs) {
                composeRule.onNodeWithTag(
                    settingsPageTransitionSceneTag(SettingsPage.ExternalLyrics), useUnmergedTree = true
                ).isDisplayed()
            }
            logPhase("detail-route-ready")
            composeRule.waitForIdle()
            val detail = composeRule.onNode(
                hasScrollToIndexAction() and hasAnyAncestor(
                    hasTestTag(settingsPageTransitionSceneTag(SettingsPage.ExternalLyrics))
                ),
                useUnmergedTree = true
            )
            if (allControls) {
                assertAllControls(detail)
            } else {
                assertBottomRowTouches(TranslationTitle, detail)
                assertBottomRowTouches(RevealTitle, detail)
            }
            assertMiniPlayer(song.name)
        } catch (error: Throwable) {
            failure = error
            captureOptional("failure")
            if (!capturePrefix.isNullOrBlank()) {
                UiFailureDiagnostics.record("external-lyrics-page-touch-$uiDensityScale", error)
            }
            throw error
        } finally {
            logPhase("cleanup-before")
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { scenario?.close() }
            cleanup { instrumentation.runOnMainSync { PlayerManager.release() } }
            // 恢复本包该项的原始 mode，appops 历史由外层模拟器快照恢复
            cleanup { originalOverlayMode?.let(::setOverlayMode) }
            cleanup { runBlocking { originalQueue.restore(context) } }
            cleanup { runBlocking { context.dataStore.updateData { originalSettings } } }
            cleanup { runBlocking { context.debugBuildWarningDataStore.updateData { originalWarning } } }
            originalCaches.forEach { saved -> cleanup { saved.restore(context) } }
            cleanup {
                assertEquals("完整设置必须恢复原始键和值", originalSettings, runBlocking { context.dataStore.data.first() })
                assertEquals(originalWarning, runBlocking { context.debugBuildWarningDataStore.data.first() })
                originalCaches.forEach { it.assertRestored(context) }
            }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { networkFixture?.close() }
            NeriMotionStyle.clean = originalCleanMotion
            logPhase("cleanup-after")
            if (failure != null) cleanupFailures.forEach(failure::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun assertBottomRowTouches(resource: Int, viewport: SemanticsNodeInteraction) {
        swipeUntilVisible(settingRow(resource), viewport, avoidMiniPlayer = true)
        settingSwitch(resource).assertIsDisplayed().assertIsEnabled()
        assertPreference(resource, checked = false)
        touchSetting(resource, viewport, switch = true)
        assertPreference(resource, checked = true)
        touchSetting(resource, viewport, switch = true)
        assertPreference(resource, checked = false)
        // 文字区域和开关分别走原生窗口命中测试，不调用语义点击回调
        touchSetting(resource, viewport, switch = false)
        assertPreference(resource, checked = true)
        touchSetting(resource, viewport, switch = false)
        assertPreference(resource, checked = false)
    }

    private fun touchSetting(resource: Int, viewport: SemanticsNodeInteraction, switch: Boolean) {
        val target = if (switch) settingSwitch(resource) else settingRow(resource)
        touchControl(resource, viewport, target, if (switch) 0.5f else 0.35f)
    }

    private fun touchControl(
        resource: Int,
        viewport: SemanticsNodeInteraction,
        target: SemanticsNodeInteraction,
        xFraction: Float = 0.5f
    ) {
        waitForStableTarget(target, viewport, requireTarget = true)
        val stage = "${context.resources.getResourceEntryName(resource)}-${++touchSequence}"
        logTouchGeometry(resource, viewport, "$stage-before", target, xFraction)
        captureOptional("$stage-before")
        nativeTap(target, xFraction)
        composeRule.waitForIdle()
        logTouchGeometry(resource, viewport, "$stage-after", target, xFraction)
        captureOptional("$stage-after")
    }

    private fun assertPreference(resource: Int, checked: Boolean) {
        val expected = when (resource) {
            TranslationTitle -> initialPreferences.copy(showTranslation = checked)
            else -> initialPreferences.copy(revealAnimationEnabled = !checked)
        }
        val expectedState = if (checked) ToggleableState.On else ToggleableState.Off
        try {
            composeRule.waitUntil(UiTimeoutMs) {
                runBlocking { repository.floatingLyricsPreferencesFlow.first() } == expected &&
                    settingSwitch(resource).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expectedState
            }
        } catch (error: Throwable) {
            val actual = runBlocking { repository.floatingLyricsPreferencesFlow.first() }
            val toggle = runCatching {
                settingSwitch(resource).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState)
            }.getOrElse { "unavailable: ${it.message}" }
            val resourceName = context.resources.getResourceEntryName(resource)
            val message = "resource=$resourceName, density=$fixtureDensityScale, expectedToggle=$expectedState, " +
                "actualToggle=$toggle, expected=$expected, actual=$actual"
            Log.e(DiagnosticTag, message, error)
            throw AssertionError(message, error)
        }
        assertEquals("真实路由只应改变当前偏好", expected,
            runBlocking { repository.floatingLyricsPreferencesFlow.first() })
        assertFalse("对照场景不得创建外部歌词窗口", expected.enabled)
        assertFalse("触摸设置不得开始播放", PlayerManager.isPlayingFlow.value)
    }

    private fun logTouchGeometry(
        resource: Int,
        viewport: SemanticsNodeInteraction,
        stage: String,
        target: SemanticsNodeInteraction,
        xFraction: Float
    ) {
        val scroll = viewport.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        val targetNode = target.fetchSemanticsNode()
        val clickPoint = targetNode.positionOnScreen + Offset(targetNode.size.width * xFraction, targetNode.size.height / 2f)
        val resourceName = context.resources.getResourceEntryName(resource)
        Log.i(DiagnosticTag, "resource=$resourceName, density=$fixtureDensityScale, stage=$stage, " +
            "scroll=${scroll.value()}, maxScroll=${scroll.maxValue()}, actualNativeClickPoint=$clickPoint")
        if (!capturePrefix.isNullOrBlank()) {
            runCatching {
                val coordinates = targetNode.layoutInfo.coordinates
                val center = Offset(targetNode.size.width / 2f, targetNode.size.height / 2f)
                var ancestor = targetNode.layoutInfo.parentInfo
                var depth = 0
                while (ancestor != null && depth < 20) {
                    val layout = ancestor
                    Log.i(DiagnosticTag, "resource=$resourceName, stage=$stage, ancestor=$depth, " +
                        "size=${layout.width}x${layout.height}, " +
                        "targetCenterLocal=${layout.coordinates.localPositionOf(coordinates, center)}")
                    ancestor = layout.parentInfo
                    depth++
                }
            }.onFailure { Log.e(DiagnosticTag, "ancestor geometry unavailable: resource=$resourceName", it) }
        }
        for ((name, interaction) in listOf(
            "target" to target,
            "row" to settingRow(resource),
            "mini" to composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true)
        )) {
            val node = interaction.fetchSemanticsNode()
            val center = Offset(node.size.width / 2f, node.size.height / 2f)
            val centerPoint = node.positionOnScreen + center
            val transformedCenter = node.layoutInfo.coordinates.localToScreen(center)
            Log.i(DiagnosticTag, "resource=$resourceName, density=$fixtureDensityScale, stage=$stage, node=$name, " +
                "positionOnScreen=${node.positionOnScreen}, size=${node.size}, boundsInRoot=${node.boundsInRoot}, " +
                "unclippedBoundsDp=${interaction.getUnclippedBoundsInRoot()}, nativeClickPoint=$centerPoint, " +
                "localToScreenCenter=$transformedCenter, toggle=${node.config.getOrNull(SemanticsProperties.ToggleableState)}")
        }
        Log.i(DiagnosticTag, "density=$fixtureDensityScale, stage=$stage, " +
            "actual=${runBlocking { repository.floatingLyricsPreferencesFlow.first() }}")
    }

    private fun captureOptional(stage: String) {
        val prefix = capturePrefix?.takeIf { it.isNotBlank() } ?: return
        runCatching {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "screenshot unavailable" }
            try {
                val safePrefix = prefix.replace(Regex("[^a-zA-Z0-9_-]"), "-")
                val directory = checkNotNull(context.getExternalFilesDir(null))
                val file = File(directory, "$safePrefix-external-lyrics-$fixtureDensityScale-$stage.png")
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                Log.i(DiagnosticTag, "screenshot=${file.absolutePath}")
            } finally {
                bitmap.recycle()
            }
        }.onFailure { Log.e(DiagnosticTag, "optional screenshot unavailable: stage=$stage", it) }
    }

    private fun assertMiniPlayer(name: String) {
        composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNode(hasText(name) and hasAnyAncestor(hasTestTag(MiniPlayerTag)), useUnmergedTree = true)
            .assertIsDisplayed()
        assertFalse(PlayerManager.isPlayingFlow.value)
    }

    private fun settingRow(resource: Int) = composeRule.onNodeWithText(checkNotNull(labels[resource]))

    private fun settingSwitch(resource: Int) = settingRow(resource).onChildren().filterToOne(isToggleable())

    private fun swipeUntilVisible(
        target: SemanticsNodeInteraction,
        viewport: SemanticsNodeInteraction,
        avoidMiniPlayer: Boolean
    ): SemanticsNode {
        var lastGeometry = "not sampled"
        repeat(28) { attempt ->
            waitForStableTarget(target, viewport, requireTarget = false)
            val node = viewport.fetchSemanticsNode()
            // 手势和可见性统一使用屏幕像素，避免应用缩放后的 dp 与原生坐标混用
            val viewportBounds = node.boundsInRoot.translate(node.positionOnScreen - node.positionInRoot)
            val bottom = if (avoidMiniPlayer) minOf(viewportBounds.bottom,
                composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).fetchSemanticsNode().positionOnScreen.y)
            else viewportBounds.bottom
            val clearance = with(node.layoutInfo.density) { 4.dp.toPx() }
            val safeBounds = Rect(viewportBounds.left, viewportBounds.top, viewportBounds.right, bottom - clearance)
            assertTrue("原生滑动需要真实可用的页面区域: $safeBounds", safeBounds.width > 4f && safeBounds.height > 4f)
            val targetNode = runCatching { target.fetchSemanticsNode() }.getOrNull()
            val targetBounds = targetNode?.unclippedScreenBounds()
            if (targetBounds != null) {
                assertTrue("目标无法完整放入迷你播放器上方: target=$targetBounds, safe=$safeBounds",
                    targetBounds.height <= safeBounds.height)
                if (targetBounds.top >= safeBounds.top && targetBounds.bottom <= safeBounds.bottom && target.isDisplayed()) {
                    return checkNotNull(targetNode)
                }
            }
            val correction = when {
                targetBounds == null -> Float.POSITIVE_INFINITY
                targetBounds.top < safeBounds.top -> targetBounds.top - safeBounds.top
                else -> targetBounds.bottom - safeBounds.bottom
            }
            val scrollDown = correction > 0f
            val x = safeBounds.left + 2f
            val start = Offset(x, (node.positionOnScreen.y + node.size.height * if (scrollDown) 0.65f else 0.2f)
                .coerceIn(safeBounds.top + 1f, safeBounds.bottom - 1f))
            val end = Offset(x, (node.positionOnScreen.y + node.size.height * if (scrollDown) 0.2f else 0.65f)
                .coerceIn(safeBounds.top + 1f, safeBounds.bottom - 1f))
            val coarseDistance = abs(start.y - end.y)
            val precise = targetBounds != null && abs(correction) <= coarseDistance
            val (swipeStart, swipeEnd) = if (precise) {
                val touchSlop = node.layoutInfo.viewConfiguration.touchSlop
                val slack = (safeBounds.height - checkNotNull(targetBounds).height) / 2f
                val distance = minOf(coarseDistance, abs(correction) + touchSlop + minOf(touchSlop, slack))
                val half = distance / 2f * if (scrollDown) 1f else -1f
                Offset(x, safeBounds.center.y + half) to Offset(x, safeBounds.center.y - half)
            } else start to end
            lastGeometry = "attempt=${attempt + 1}, target=$targetBounds, safe=$safeBounds, " +
                "correction=$correction, precise=$precise, start=$swipeStart, end=$swipeEnd"
            Log.i(DiagnosticTag, "density=$fixtureDensityScale, swipe=$lastGeometry")
            nativeSwipe(swipeStart, swipeEnd, stopBeforeUp = precise)
            composeRule.waitForIdle()
        }
        throw AssertionError("真实滑动后目标仍未完整露出迷你播放器上方: $lastGeometry")
    }

    private fun SemanticsNode.unclippedScreenBounds(): Rect? {
        // LazyColumn 预组合节点的坐标只有在自身和祖先都已放置后才有意义
        var layout = layoutInfo
        while (true) {
            if (!layout.isPlaced) return null
            layout = layout.parentInfo ?: break
        }
        val position = positionOnScreen
        return Rect(position.x, position.y, position.x + size.width, position.y + size.height)
    }

    private data class ScrollFrameSample(
        val value: Float,
        val maxValue: Float,
        val targetPosition: Offset?,
        val targetSize: IntSize?,
        val targetBounds: Rect?,
        val viewportBounds: Rect,
        val miniPlayerBounds: Rect?
    )

    private fun waitForStableTarget(
        target: SemanticsNodeInteraction,
        viewport: SemanticsNodeInteraction,
        requireTarget: Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + UiTimeoutMs
        var previous: ScrollFrameSample? = null
        var stableSamples = 0
        while (SystemClock.uptimeMillis() < deadline) {
            awaitHardwareFrame(deadline)
            val viewportNode = viewport.fetchSemanticsNode()
            val scroll = viewportNode.config[SemanticsProperties.VerticalScrollAxisRange]
            val node = runCatching { target.fetchSemanticsNode() }.getOrNull()
            val miniPlayer = composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).fetchSemanticsNode()
            val sample = ScrollFrameSample(scroll.value(), scroll.maxValue(),
                node?.positionOnScreen, node?.size, node?.boundsInRoot,
                viewportNode.boundsInRoot.translate(viewportNode.positionOnScreen - viewportNode.positionInRoot),
                miniPlayer.unclippedScreenBounds())
            stableSamples = if (sample == previous) stableSamples + 1 else 1
            Log.i(DiagnosticTag, "density=$fixtureDensityScale, settling=$sample, stableFrames=$stableSamples")
            if (stableSamples >= 3 && (!requireTarget || node != null)) return
            previous = sample
        }
        fail("真实渲染帧中滚动、目标、页面及迷你播放器未连续三次稳定: last=$previous, stableFrames=$stableSamples")
    }

    private fun awaitHardwareFrame(deadline: Long) {
        val committed = AtomicBoolean(false)
        var observer: ViewTreeObserver? = null
        var callback: Runnable? = null
        try {
            logPhase("hardware-frame-register-before")
            activeScenario.onActivity { activity ->
                val decor = activity.window.decorView
                check(decor.isAttachedToWindow && decor.isHardwareAccelerated)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val frameObserver = decor.viewTreeObserver
                    val frameCallback = Runnable {
                        committed.set(true)
                        logPhase("hardware-frame-committed")
                    }
                    observer = frameObserver
                    callback = frameCallback
                    frameObserver.registerFrameCommitCallback(frameCallback)
                    decor.invalidate()
                } else {
                    error("滚动停止验证需要 API 29 及以上的硬件帧提交")
                }
            }
            logPhase("hardware-frame-register-after")
            composeRule.waitUntil(timeoutMillis = (deadline - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                committed.get()
            }
        } finally {
            instrumentation.runOnMainSync {
                val frameObserver = observer
                val frameCallback = callback
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && frameObserver?.isAlive == true && frameCallback != null) {
                    frameObserver.unregisterFrameCommitCallback(frameCallback)
                }
            }
            logPhase("hardware-frame-unregistered")
        }
    }

    private fun logPhase(phase: String) {
        Log.i(DiagnosticTag, "density=$fixtureDensityScale, phase=$phase")
    }

    private fun nativeTap(target: SemanticsNodeInteraction, xFraction: Float) {
        val node = target.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        val point = node.positionOnScreen + Offset(node.size.width * xFraction, node.size.height / 2f)
        val downTime = SystemClock.uptimeMillis()
        injectTouch(downTime, MotionEvent.ACTION_DOWN, point)
        injectTouch(downTime, MotionEvent.ACTION_UP, point)
    }

    private fun nativeSwipe(start: Offset, end: Offset, stopBeforeUp: Boolean) {
        val downTime = SystemClock.uptimeMillis()
        injectTouch(downTime, MotionEvent.ACTION_DOWN, start)
        for (step in 1..12) {
            SystemClock.sleep(20L)
            injectTouch(downTime, MotionEvent.ACTION_MOVE, start + (end - start) * (step / 12f))
        }
        if (stopBeforeUp) {
            // 超过速度估计的历史窗口后补静止样本，避免末段微调在抬指后继续惯性滑动
            SystemClock.sleep(120L)
            injectTouch(downTime, MotionEvent.ACTION_MOVE, end)
        }
        injectTouch(downTime, MotionEvent.ACTION_UP, end)
    }

    private fun injectTouch(downTime: Long, action: Int, point: Offset) {
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, point.x, point.y, 0)
            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        try {
            assertTrue("宿主应接收原生触摸", instrumentation.uiAutomation.injectInputEvent(event, true))
        } finally {
            event.recycle()
        }
    }

    private data class SwitchCase(
        val title: Int,
        val checked: (FloatingLyricsPreferences) -> Boolean,
        val update: (FloatingLyricsPreferences, Boolean) -> FloatingLyricsPreferences
    )

    private data class SliderCase(
        val title: Int,
        val value: (FloatingLyricsPreferences) -> Float,
        val update: (FloatingLyricsPreferences, Float) -> FloatingLyricsPreferences
    )

    private fun assertAllControls(viewport: SemanticsNodeInteraction) {
        val switches = listOf(
            SwitchCase(CoreCommonR.string.settings_floating_lyrics_hide_in_app, { it.hideInApp },
                { prefs, value -> prefs.copy(hideInApp = value) }),
            SwitchCase(CoreCommonR.string.settings_floating_lyrics_long_press_drag, { it.longPressDragEnabled },
                { prefs, value -> prefs.copy(longPressDragEnabled = value) }),
            SwitchCase(TranslationTitle, { it.showTranslation }, { prefs, value -> prefs.copy(showTranslation = value) }),
            SwitchCase(RevealTitle, { !it.revealAnimationEnabled },
                { prefs, value -> prefs.copy(revealAnimationEnabled = !value) })
        )
        val fixedSliders = listOf(
            SliderCase(CoreCommonR.string.settings_floating_lyrics_font_size, { it.fontSizeSp },
                { prefs, value -> prefs.copy(fontSizeSp = value) }),
            SliderCase(CoreCommonR.string.settings_floating_lyrics_lyric_shadow_blur, { it.outlineWidthDp },
                { prefs, value -> prefs.copy(outlineWidthDp = value) }),
            SliderCase(CoreCommonR.string.settings_floating_lyrics_translation_shadow_blur, { it.translationOutlineWidthDp },
                { prefs, value -> prefs.copy(translationOutlineWidthDp = value) }),
            SliderCase(CoreCommonR.string.settings_floating_lyrics_lyric_alpha, { it.lyricAlpha },
                { prefs, value -> prefs.copy(lyricAlpha = value) }),
            SliderCase(CoreCommonR.string.settings_floating_lyrics_translation_alpha, { it.translationAlpha },
                { prefs, value -> prefs.copy(translationAlpha = value) }),
            SliderCase(CoreCommonR.string.settings_floating_lyrics_max_width, { it.maxWidthDp },
                { prefs, value -> prefs.copy(maxWidthDp = value) }),
            SliderCase(CoreCommonR.string.settings_floating_lyrics_landscape_max_width, { it.landscapeMaxWidthDp },
                { prefs, value -> prefs.copy(landscapeMaxWidthDp = value) })
        )
        for (enabled in listOf(false, true)) {
            if (expectedPreferences.enabled != enabled) setEnabledByTouch(enabled, viewport)
            for (spec in switches) {
                val original = spec.checked(expectedPreferences)
                for ((checked, switch) in listOf(!original to true, original to false)) {
                    swipeUntilVisible(settingRow(spec.title), viewport, avoidMiniPlayer = true)
                    val expected = spec.update(expectedPreferences, checked)
                    val expectedToggle = if (checked) ToggleableState.On else ToggleableState.Off
                    touchSetting(spec.title, viewport, switch)
                    expectCompletePreferences(spec.title, expected)
                    // 持久化完成后，宿主仍需消费偏好更新并重新组合开关
                    composeRule.waitUntil(UiTimeoutMs) {
                        runBlocking { repository.floatingLyricsPreferencesFlow.first() } == expected &&
                            settingSwitch(spec.title).fetchSemanticsNode().config[SemanticsProperties.ToggleableState] == expectedToggle
                    }
                    assertEquals(expectedToggle,
                        settingSwitch(spec.title).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
                }
            }
            for ((label, count) in listOf(CoreCommonR.string.settings_floating_lyrics_sentence_three to 3,
                CoreCommonR.string.settings_floating_lyrics_sentence_two to 2,
                CoreCommonR.string.settings_floating_lyrics_sentence_one to 1)) {
                selectOption(CoreCommonR.string.settings_floating_lyrics_sentence_count, label, viewport,
                    expectedPreferences.copy(sentenceCount = count))
            }
            selectOption(CoreCommonR.string.settings_floating_lyrics_long_line_mode,
                CoreCommonR.string.settings_floating_lyrics_long_line_wrap, viewport,
                expectedPreferences.copy(longLineMode = "wrap"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_long_line_mode,
                CoreCommonR.string.settings_floating_lyrics_long_line_scroll, viewport,
                expectedPreferences.copy(longLineMode = "scroll"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_text_color,
                CoreCommonR.string.settings_floating_lyrics_color_red, viewport,
                expectedPreferences.copy(textColorHex = "FF5A64"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_text_color,
                CoreCommonR.string.settings_floating_lyrics_color_white, viewport,
                expectedPreferences.copy(textColorHex = "FFFFFF"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_render_style,
                CoreCommonR.string.settings_floating_lyrics_render_outline, viewport,
                expectedPreferences.copy(renderStyle = "outline"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_outline_color,
                CoreCommonR.string.settings_floating_lyrics_color_blue, viewport,
                expectedPreferences.copy(outlineColorHex = "4C9BFF"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_outline_color,
                CoreCommonR.string.settings_floating_lyrics_color_black, viewport,
                expectedPreferences.copy(outlineColorHex = "000000"))
            selectOption(CoreCommonR.string.settings_floating_lyrics_render_style,
                CoreCommonR.string.settings_floating_lyrics_render_shadow, viewport,
                expectedPreferences.copy(renderStyle = "shadow"))
            for (spec in fixedSliders) dragSlider(spec, viewport)
            selectPositionOrientation(landscape = false, viewport)
            dragSlider(SliderCase(CoreCommonR.string.settings_floating_lyrics_position_x, { it.positionX },
                { prefs, value -> prefs.copy(positionX = value) }), viewport)
            dragSlider(SliderCase(CoreCommonR.string.settings_floating_lyrics_position_y, { it.positionY },
                { prefs, value -> prefs.copy(positionY = value) }), viewport)
            selectPositionOrientation(landscape = true, viewport)
            dragSlider(SliderCase(CoreCommonR.string.settings_floating_lyrics_position_x, { it.landscapePositionX },
                { prefs, value -> prefs.copy(landscapePositionX = value) }), viewport)
            dragSlider(SliderCase(CoreCommonR.string.settings_floating_lyrics_position_y, { it.landscapePositionY },
                { prefs, value -> prefs.copy(landscapePositionY = value) }), viewport)
            selectPositionOrientation(landscape = false, viewport)
            for ((label, alignment) in listOf(CoreCommonR.string.settings_floating_lyrics_align_left to "left",
                CoreCommonR.string.settings_floating_lyrics_align_right to "right",
                CoreCommonR.string.settings_floating_lyrics_align_center to "center")) {
                selectOption(CoreCommonR.string.settings_floating_lyrics_alignment, label, viewport,
                    expectedPreferences.copy(alignment = alignment))
            }
        }
        setEnabledByTouch(enabled = false, viewport)
    }

    private fun setEnabledByTouch(enabled: Boolean, viewport: SemanticsNodeInteraction) {
        val title = CoreCommonR.string.settings_floating_lyrics_enable
        swipeUntilVisible(settingRow(title), viewport, avoidMiniPlayer = true)
        touchSetting(title, viewport, switch = true)
        expectCompletePreferences(title, expectedPreferences.copy(enabled = enabled))
        assertEquals(if (enabled) ToggleableState.On else ToggleableState.Off,
            settingSwitch(title).fetchSemanticsNode().config[SemanticsProperties.ToggleableState])
    }

    private fun selectOption(
        title: Int,
        label: Int,
        viewport: SemanticsNodeInteraction,
        expected: FloatingLyricsPreferences
    ) {
        val target = settingRow(title).onChildren().filterToOne(hasText(checkNotNull(labels[label])))
        swipeUntilVisible(target, viewport, avoidMiniPlayer = true)
        touchControl(title, viewport, target)
        expectCompletePreferences(title, expected)
    }

    private fun selectPositionOrientation(landscape: Boolean, viewport: SemanticsNodeInteraction) {
        val title = CoreCommonR.string.settings_floating_lyrics_orientation
        val label = if (landscape) CoreCommonR.string.settings_floating_lyrics_orientation_landscape
        else CoreCommonR.string.settings_floating_lyrics_orientation_portrait
        selectOption(title, label, viewport, expectedPreferences)
        val x = if (landscape) expectedPreferences.landscapePositionX else expectedPreferences.positionX
        val y = if (landscape) expectedPreferences.landscapePositionY else expectedPreferences.positionY
        assertEquals("方向只切换编辑目标，不改另一方向配置", x,
            slider(CoreCommonR.string.settings_floating_lyrics_position_x).fetchSemanticsNode()
                .config[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
        assertEquals(y, slider(CoreCommonR.string.settings_floating_lyrics_position_y).fetchSemanticsNode()
            .config[SemanticsProperties.ProgressBarRangeInfo].current, 0.0001f)
    }

    private fun slider(title: Int) = settingRow(title).onChildren().filterToOne(
        SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)
    )

    private fun dragSlider(spec: SliderCase, viewport: SemanticsNodeInteraction) {
        val target = slider(spec.title)
        val node = swipeUntilVisible(target, viewport, avoidMiniPlayer = true)
        val range = node.config[SemanticsProperties.ProgressBarRangeInfo]
        val initialTolerance = if (range.steps > 0) {
            (range.range.endInclusive - range.range.start) / (range.steps + 1) / 2f + 0.0001f
        } else 0.0001f
        assertEquals("resource=${context.resources.getResourceEntryName(spec.title)}, steps=${range.steps}, " +
            "range=${range.range}, initialTolerance=$initialTolerance",
            spec.value(expectedPreferences), range.current, initialTolerance)
        val toEnd = abs(range.current - range.range.endInclusive) > 0.0001f
        val next = if (toEnd) range.range.endInclusive else range.range.start
        val fraction = ((range.current - range.range.start) / (range.range.endInclusive - range.range.start))
            .coerceIn(0.05f, 0.95f)
        logTouchGeometry(spec.title, viewport, "slider-before", target, fraction)
        // 离线提示可能在诊断期间抬高迷你播放器，下发手势前重新确认目标没有被遮挡
        val touchNode = swipeUntilVisible(target, viewport, avoidMiniPlayer = true)
        val start = touchNode.positionOnScreen + Offset(touchNode.size.width * fraction, touchNode.size.height / 2f)
        val end = touchNode.positionOnScreen + Offset(
            if (toEnd) touchNode.size.width - 1f else 1f, touchNode.size.height / 2f)
        val downTime = SystemClock.uptimeMillis()
        var released = false
        try {
            injectTouch(downTime, MotionEvent.ACTION_DOWN, start)
            for (step in 1..12) {
                SystemClock.sleep(20L)
                injectTouch(downTime, MotionEvent.ACTION_MOVE, start + (end - start) * (step / 12f))
            }
            composeRule.waitUntil(UiTimeoutMs) {
                abs(target.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current - next) < 0.0001f
            }
            assertEquals("滑动预览不能提前持久化", expectedPreferences,
                runBlocking { repository.floatingLyricsPreferencesFlow.first() })
            injectTouch(downTime, MotionEvent.ACTION_UP, end)
            released = true
        } finally {
            if (!released) injectTouch(downTime, MotionEvent.ACTION_CANCEL, end)
        }
        expectCompletePreferences(spec.title, spec.update(expectedPreferences, next))
        logTouchGeometry(spec.title, viewport, "slider-after", target, if (toEnd) 1f else 0f)
    }

    private fun expectCompletePreferences(resource: Int, expected: FloatingLyricsPreferences) {
        try {
            composeRule.waitUntil(UiTimeoutMs) { runBlocking { repository.floatingLyricsPreferencesFlow.first() } == expected }
        } catch (error: Throwable) {
            val actual = runBlocking { repository.floatingLyricsPreferencesFlow.first() }
            throw AssertionError("resource=${context.resources.getResourceEntryName(resource)}, expected=$expected, actual=$actual", error)
        }
        assertEquals(expected, runBlocking { repository.floatingLyricsPreferencesFlow.first() })
        assertFalse("所有设置交互都不得启动播放", PlayerManager.isPlayingFlow.value)
        expectedPreferences = expected
    }

    private fun readOverlayMode(): Int {
        val manager = checkNotNull(context.getSystemService(AppOpsManager::class.java))
        return if (Build.VERSION.SDK_INT >= 36) {
            manager.checkOpRawNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.applicationInfo.uid, context.packageName, null)
        } else {
            readLegacyOverlayMode(manager)
        }
    }

    private fun readSupportedPackageOverlayMode(): Int {
        val output = readShell("cmd appops get --user current ${context.packageName} SYSTEM_ALERT_WINDOW")
        val lines = output.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        check(lines.isNotEmpty()) { "appop 查询为空，禁止改变权限" }
        check(lines.none { it.contains("Uid mode:") }) { "存在 UID appop override，本夹具不支持改变权限" }
        val modes = "(allow|ignore|deny|default|foreground)"
        val name = if (lines.size == 2 && lines[0] == "No operations.") {
            checkNotNull(Regex("Default mode: $modes").matchEntire(lines[1])) {
                "无法识别 appop 默认 mode，禁止改变权限"
            }.groupValues[1]
        } else {
            check(lines.size == 1) { "appop 查询包含未知或重复记录，禁止改变权限" }
            val history = "(?:; (?:time|rejectTime)=[^;\\s]+ ago|; duration=[^;\\s]+| \\(running\\))*"
            checkNotNull(Regex("SYSTEM_ALERT_WINDOW: $modes$history").matchEntire(lines.single())) {
                "无法识别本包 appop 记录，禁止改变权限"
            }.groupValues[1]
        }
        val mode = listOf(AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED,
            AppOpsManager.MODE_DEFAULT, AppOpsManager.MODE_FOREGROUND).single { overlayModeName(it) == name }
        assertEquals("无 UID override 时包 mode 必须与 raw 查询一致", mode, readOverlayMode())
        return mode
    }

    // API 30 到 35 没有新的 raw 查询入口，必须保留旧入口才能区分 default 和 foreground
    @Suppress("DEPRECATION")
    private fun readLegacyOverlayMode(manager: AppOpsManager): Int {
        if (Build.VERSION.SDK_INT < 29) error("原始 appop mode 查询需要 API 29 及以上")
        return manager.unsafeCheckOpRawNoThrow(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
            context.applicationInfo.uid, context.packageName)
    }

    private fun overlayModeName(mode: Int): String = when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allow"
        AppOpsManager.MODE_IGNORED -> "ignore"
        AppOpsManager.MODE_ERRORED -> "deny"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> error("未知 appop mode 不允许写入: $mode")
    }

    private fun setOverlayMode(mode: Int) {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val command = "cmd appops set --user current ${context.packageName} SYSTEM_ALERT_WINDOW ${overlayModeName(mode)}"
        val output = readShell(command)
        assertTrue("本包悬浮权限设置失败: $output", output.isEmpty())
        assertEquals("必须精确恢复或设置原始 mode", mode, readOverlayMode())
    }

    private fun readShell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText().trim() }
    }

    private data class SavedSharedPreferences(val name: String, val values: Map<String, Any>) {
        fun restore(context: Context) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for ((key, value) in values) {
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, copyStringSet(value))
                    else -> error("不支持的同步缓存类型: cache=$name, key=$key")
                }
            }
            assertTrue("同步缓存必须持久恢复: $name", editor.commit())
            assertRestored(context)
        }

        fun assertRestored(context: Context) {
            assertEquals("同步缓存必须恢复原始键集合及值: $name", values,
                copyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        }

        companion object {
            fun read(context: Context, name: String): SavedSharedPreferences = SavedSharedPreferences(
                name, copyValues(context.getSharedPreferences(name, Context.MODE_PRIVATE))
            )

            private fun copyValues(preferences: SharedPreferences): Map<String, Any> = preferences.all.mapValues { (key, value) ->
                when (value) {
                    is Boolean -> value
                    is String -> value
                    is Int -> value
                    is Long -> value
                    is Float -> value
                    is Set<*> -> copyStringSet(value)
                    else -> error("不支持的同步缓存类型，禁止启动夹具: key=$key")
                }
            }

            private fun copyStringSet(value: Set<*>): Set<String> = value.map { entry ->
                check(entry is String) { "同步缓存集合必须只包含字符串" }
                entry
            }.toSet()
        }
    }

    private fun createPausedSong(directory: File): PersistedSongItem {
        val audio = File(directory, "fixture.wav")
        val pcm = ByteArray(8_000 * 10 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8_000).putInt(16_000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).array()
        audio.outputStream().use { it.write(header); it.write(pcm) }
        val cover = File(directory, "cover.png")
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.rgb(45, 94, 140))
            cover.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        return PersistedSongItem(
            id = -System.nanoTime(), name = "External lyrics touch fixture", artist = "Offline fixture",
            album = "Test", albumId = 0, durationMs = 10_000L, coverUrl = cover.absolutePath,
            mediaUri = audio.absolutePath, localFilePath = audio.absolutePath, localFileName = audio.name,
            matchedLyric = "[00:00.00]Offline fixture\n[00:03.00]Second fixture line",
            matchedTranslatedLyric = "[00:00.00]测试翻译\n[00:03.00]第二行翻译"
        )
    }

    private data class SavedPlaybackQueue(
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
            suspend fun read(context: Context): SavedPlaybackQueue {
                val store = PlaybackQueueRoomStore(NeriUserDataDatabase.getInstance(context))
                return SavedPlaybackQueue(store.isRoomPrimary(), store.readIfRoomPrimary(),
                    QueueFiles.associateWith { name -> File(context.filesDir, name).takeIf(File::exists)?.readBytes() })
            }
        }
    }

    private companion object {
        const val UiTimeoutMs = 20_000L
        const val MiniPlayerTag = "appMiniPlayer"
        const val DiagnosticTag = "ExternalLyricsPageTouch"
        val TranslationTitle = CoreCommonR.string.settings_floating_lyrics_show_translation
        val RevealTitle = CoreCommonR.string.settings_floating_lyrics_disable_reveal_animation
        val QueueFiles = listOf("last_playlist.json", "last_playback_state.json")
        val CachePreferenceNames = listOf("startup_progress_cache", "theme_snapshot_cache", "playback_snapshot_cache")
        val ControlLabels = listOf(
            TranslationTitle, RevealTitle,
            CoreCommonR.string.settings_floating_lyrics_enable,
            CoreCommonR.string.settings_floating_lyrics_hide_in_app,
            CoreCommonR.string.settings_floating_lyrics_long_press_drag,
            CoreCommonR.string.settings_floating_lyrics_sentence_count,
            CoreCommonR.string.settings_floating_lyrics_sentence_one,
            CoreCommonR.string.settings_floating_lyrics_sentence_two,
            CoreCommonR.string.settings_floating_lyrics_sentence_three,
            CoreCommonR.string.settings_floating_lyrics_long_line_mode,
            CoreCommonR.string.settings_floating_lyrics_long_line_scroll,
            CoreCommonR.string.settings_floating_lyrics_long_line_wrap,
            CoreCommonR.string.settings_floating_lyrics_text_color,
            CoreCommonR.string.settings_floating_lyrics_render_style,
            CoreCommonR.string.settings_floating_lyrics_render_shadow,
            CoreCommonR.string.settings_floating_lyrics_render_outline,
            CoreCommonR.string.settings_floating_lyrics_outline_color,
            CoreCommonR.string.settings_floating_lyrics_color_red,
            CoreCommonR.string.settings_floating_lyrics_color_white,
            CoreCommonR.string.settings_floating_lyrics_color_blue,
            CoreCommonR.string.settings_floating_lyrics_color_black,
            CoreCommonR.string.settings_floating_lyrics_font_size,
            CoreCommonR.string.settings_floating_lyrics_lyric_shadow_blur,
            CoreCommonR.string.settings_floating_lyrics_translation_shadow_blur,
            CoreCommonR.string.settings_floating_lyrics_lyric_alpha,
            CoreCommonR.string.settings_floating_lyrics_translation_alpha,
            CoreCommonR.string.settings_floating_lyrics_max_width,
            CoreCommonR.string.settings_floating_lyrics_landscape_max_width,
            CoreCommonR.string.settings_floating_lyrics_orientation,
            CoreCommonR.string.settings_floating_lyrics_orientation_portrait,
            CoreCommonR.string.settings_floating_lyrics_orientation_landscape,
            CoreCommonR.string.settings_floating_lyrics_position_x,
            CoreCommonR.string.settings_floating_lyrics_position_y,
            CoreCommonR.string.settings_floating_lyrics_alignment,
            CoreCommonR.string.settings_floating_lyrics_align_left,
            CoreCommonR.string.settings_floating_lyrics_align_center,
            CoreCommonR.string.settings_floating_lyrics_align_right
        )
    }
}
