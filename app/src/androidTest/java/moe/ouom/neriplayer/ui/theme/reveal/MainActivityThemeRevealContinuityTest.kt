package moe.ouom.neriplayer.ui.theme.reveal

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.ViewTreeObserver
import android.view.Window
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.graphics.HardwareRendererCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.activity.MainActivity
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.model.settings.appearance.AdvancedBlurQuality
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.appearance.PreviewThemePromptRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.traffic.hasLikelyInternetAccess
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testutil.EmulatorOfflineNetworkFixture
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
class MainActivityThemeRevealContinuityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun cleanThemeRoundTripsKeepRealBackgroundsAndNavigationUsable() = verifyRoundTrips(UiStyle.Clean)

    @Test
    fun materialThemeRoundTripsKeepRealBackgroundsAndNavigationUsable() = verifyRoundTrips(UiStyle.Material)

    @Test
    fun cleanMainTabScrollingViewportsReachTheWindowBottom() = verifyRoundTrips(UiStyle.Clean, pageViewportOnly = true)

    @Test
    fun materialMainTabScrollingViewportsReachTheWindowBottom() = verifyRoundTrips(UiStyle.Material, pageViewportOnly = true)

    private fun verifyRoundTrips(style: UiStyle, pageViewportOnly: Boolean = false) {
        assumeTrue("整应用夹具仅在模拟器运行", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeNoSyncTargets()
        assumeComposeHostAvailable()
        val settings = SettingsRepository(context)
        val savedSettings = runBlocking { context.dataStore.data.first() }
        val savedWarning = runBlocking { context.debugBuildWarningDataStore.data.first() }
        val savedCaches = CacheNames.map { SavedCache.read(context, it) }
        val savedMotion = NeriMotionStyle.clean
        val savedNightMode = AppCompatDelegate.getDefaultNightMode().let { mode ->
            // UNSPECIFIED 与 FOLLOW_SYSTEM 解析相同，但公开 setter 不接受前者
            if (mode == AppCompatDelegate.MODE_NIGHT_UNSPECIFIED) AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM else mode
        }
        val savedLanguage = LanguageManager.getCurrentLanguage(context)
        val localized = LanguageManager.localizedContext(context, savedLanguage)
        val directory = File(context.cacheDir, "main-theme-reveal-${style.name}-${System.nanoTime()}")
        var network: EmulatorOfflineNetworkFixture? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var noImageFrame: Bitmap? = null
        var failure: Throwable? = null
        val autoAdvance = composeRule.mainClock.autoAdvance
        try {
            if (pageViewportOnly) {
                assumeTrue("真实探索 grid 需要普通联网页面，不能用离线 fallback 代替", context.hasLikelyInternetAccess())
            } else {
                network = EmulatorOfflineNetworkFixture.start(context)
            }
            check(directory.mkdirs())
            val background = createBackground(directory)
            runBlocking {
                DebugBuildWarningRepository(context).acknowledge()
                PreviewThemePromptRepository(context).answer(enable = false)
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(true)
                settings.setDefaultStartDestination(Destinations.Settings.route)
                settings.setUiStyle(style)
                settings.setThemeMode(followSystemDark = false, forceDark = false)
                settings.setDynamicColor(false)
                settings.setUiDensityScale(1f)
                settings.setAdvancedBlurEnabled(true)
                settings.setEnhancedAdvancedBlurEnabled(true)
                settings.setAdvancedBlurQuality(AdvancedBlurQuality.High)
                settings.setBackgroundImageUri(null)
                settings.setBackgroundImageBlur(12f)
                settings.setBackgroundImageAlpha(0.75f)
                if (pageViewportOnly) settings.setInternationalizationEnabled(false)
            }
            val activeScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activeScenario
            lateinit var originalActivity: MainActivity
            activeScenario.onActivity { originalActivity = it }
            val settingsLabel = localized.getString(AppR.string.nav_settings)
            val libraryLabel = localized.getString(AppR.string.nav_library)
            val darkAction = localized.getString(CoreCommonR.string.settings_theme_toggle_dark)
            val lightAction = localized.getString(CoreCommonR.string.settings_theme_toggle_light)
            awaitDisplayed(darkAction)
            navigationTab(settingsLabel).assertIsSelected()
            composeRule.mainClock.autoAdvance = false
            advanceFrames(SettleFrames)

            if (pageViewportOnly) {
                UiFailureDiagnostics.onFailure("main-page-viewports-${style.name}") {
                    assertMainPageViewports(style, activeScenario, localized)
                }
                return
            }

            UiFailureDiagnostics.onFailure("main-theme-reveal-${style.name}") {
                for (withImage in listOf(false, true)) {
                    runBlocking { settings.setBackgroundImageUri(background.toURI().toString().takeIf { withImage }) }
                    advanceFrames(SettleFrames)
                    if (withImage) {
                        awaitDisplayed(localized.getString(CoreCommonR.string.cd_app_background))
                        awaitLoadedBackground(activeScenario, checkNotNull(noImageFrame))
                    }
                    val initial = captureWindow(activeScenario)
                    try {
                        assertNonEmpty(initial, "$style image=$withImage initial")
                        if (!withImage) noImageFrame = initial.copy(Bitmap.Config.ARGB_8888, false)
                        toggleAndCapture(settings, activeScenario, originalActivity, darkAction, lightAction, true)
                        toggleAndCapture(settings, activeScenario, originalActivity, lightAction, darkAction, false)
                        val returned = captureWindow(activeScenario)
                        try {
                            assertNonEmpty(returned, "$style image=$withImage returned")
                            assertTrue("浅色往返后必须重新显示当前页面，而非残留深色截图",
                                changedPixelFraction(initial, returned) < 0.15)
                        } finally {
                            returned.recycle()
                        }
                        assertTabsRemainUsable(activeScenario, settingsLabel, libraryLabel)
                        activeScenario.onActivity { assertSame("主题往返不能重建 Activity", originalActivity, it) }
                        assertEquals("测试不应修改当前语言", savedLanguage, LanguageManager.getCurrentLanguage(context))
                    } finally {
                        initial.recycle()
                    }
                }
            }
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            fun cleanup(action: () -> Unit) {
                runCatching(action).exceptionOrNull()?.let(cleanupFailures::add)
            }
            cleanup { composeRule.mainClock.autoAdvance = true }
            cleanup { scenario?.close() }
            cleanup { noImageFrame?.recycle() }
            cleanup { runBlocking { context.dataStore.updateData { savedSettings } } }
            cleanup { runBlocking { context.debugBuildWarningDataStore.updateData { savedWarning } } }
            cleanup {
                instrumentation.runOnMainSync {
                    AppCompatDelegate.setDefaultNightMode(savedNightMode)
                    assertEquals("应恢复进程默认夜间模式", savedNightMode, AppCompatDelegate.getDefaultNightMode())
                }
            }
            cleanup { savedCaches.forEach { it.restore(context) } }
            cleanup { NeriMotionStyle.clean = savedMotion }
            cleanup { network?.close() }
            cleanup { check(!directory.exists() || directory.deleteRecursively()) }
            cleanup { composeRule.mainClock.autoAdvance = autoAdvance }
            if (failure != null) cleanupFailures.forEach(failure::addSuppressed)
            else if (cleanupFailures.isNotEmpty()) {
                val first = cleanupFailures.first()
                cleanupFailures.drop(1).forEach(first::addSuppressed)
                throw first
            }
        }
    }

    private fun assumeNoSyncTargets() {
        val noTargets = runCatching {
            val keysets = setOf("__androidx_security_crypto_encrypted_prefs_key_keyset__",
                "__androidx_security_crypto_encrypted_prefs_value_keyset__")
            val webDavEmpty = context.getSharedPreferences("webdav_secure_prefs", Context.MODE_PRIVATE)
                .all.keys.all { it in keysets }
            val github = SecureTokenStorage(context)
            webDavEmpty && github.isPersistent && github.getToken().isNullOrEmpty() &&
                github.getRepoOwner().isNullOrEmpty() && github.getRepoName().isNullOrEmpty()
        }.getOrDefault(false)
        assumeTrue("专用模拟器不能配置 GitHub 或 WebDAV 同步目标", noTargets)
    }

    private fun assertMainPageViewports(style: UiStyle, scenario: ActivityScenario<MainActivity>, localized: Context) {
        val directory = File(context.cacheDir, "main-page-viewports-${style.name}-${System.nanoTime()}")
        check(directory.mkdirs())
        val pages = listOf("home" to AppR.string.nav_home, "explore" to AppR.string.nav_explore,
            "settings" to AppR.string.nav_settings)
        val records = JSONArray()
        val failures = mutableListOf<String>()
        var homeControlPassed = false
        for ((page, labelId) in pages) {
            val label = localized.getString(labelId)
            navigationTab(label).performClick()
            advanceFrames(SettleFrames)
            navigationTab(label).assertIsSelected()
            val scrolling = composeRule.onAllNodes(
                hasScrollToIndexAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
                useUnmergedTree = true
            )
            val nodes = scrolling.fetchSemanticsNodes()
            val displayed = nodes.indices.filter { scrolling[it].isDisplayed() }
            assertEquals("真实当前页面应只有一个竖向滚动视口: $page", 1, displayed.size)
            val node = nodes[displayed.single()]
            val viewport = node.boundsInWindow
            val frame = captureWindow(scenario)
            var screen: Bitmap? = null
            val record = JSONObject().put("page", page).put("style", style.name)
                .put("composeClockMs", composeRule.mainClock.currentTime)
                .put("scrollerBoundsInRoot", node.boundsInRoot.toString())
                .put("scrollerBoundsInWindow", viewport.toString())
            try {
                screen = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
                File(directory, "$page-window.png").outputStream().use {
                    check(frame.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                File(directory, "$page-system.png").outputStream().use {
                    check(screen.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                scenario.onActivity { activity ->
                    val decor = activity.window.decorView
                    val insets = checkNotNull(ViewCompat.getRootWindowInsets(decor))
                    val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
                    record.put("rootWidth", decor.width).put("rootHeight", decor.height)
                        .put("rootHasFocus", activity.hasWindowFocus())
                        .put("navigationInsets", navigation.toString())
                        .put("tappableInsets", insets.getInsets(WindowInsetsCompat.Type.tappableElement()).toString())
                        .put("imeInsets", insets.getInsets(WindowInsetsCompat.Type.ime()).toString())
                        .put("imeVisible", insets.isVisible(WindowInsetsCompat.Type.ime()))
                        .put("navigationVisible", insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
                        .put("bottomGapPx", decor.height - viewport.bottom)
                    assertTrue("页面测量必须由真实 Activity 持有焦点", activity.hasWindowFocus())
                    assertTrue("页面测量时 IME 必须关闭", !insets.isVisible(WindowInsetsCompat.Type.ime()))
                    assumeTrue("首轮仅验证底部手势导航", navigation.bottom > 0 &&
                        insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom == 0)
                    val gap = decor.height - viewport.bottom
                    val reachesBottom = if (page == "home") {
                        gap >= -1f && gap < navigation.bottom - 1f
                    } else abs(gap) <= 1f
                    record.put("bottomOraclePassed", reachesBottom)
                    if (page == "home") {
                        homeControlPassed = reachesBottom
                        record.put("knownHomeOuterVerticalPaddingDp", 4)
                    }
                    if (!reachesBottom) {
                        failures += "$page viewport.bottom=${viewport.bottom}, window.bottom=${decor.height}, nav=${navigation.bottom}"
                    }
                }
            } finally {
                frame.recycle()
                screen?.recycle()
                records.put(record)
                File(directory, "bounds.json").writeText(records.toString(2))
            }
        }
        assertTrue("首页控制必须实际延伸进导航区域: ${failures.joinToString()}", homeControlPassed)
        assertTrue("真实探索/设置滚动视口必须延伸到窗口底部: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun toggleAndCapture(
        settings: SettingsRepository,
        scenario: ActivityScenario<MainActivity>,
        originalActivity: MainActivity,
        action: String,
        nextAction: String,
        dark: Boolean
    ) {
        composeRule.onNodeWithContentDescription(action).assertIsDisplayed().performClick()
        composeRule.waitUntil(UiTimeoutMs) {
            advanceFrames(1)
            runBlocking { settings.forceDarkFlow.first() } == dark
        }
        awaitDisplayed(nextAction)
        // 从持久化交接后逐帧推进，记录中间画面而不把主机等待解释为响应时延
        advanceFrames(2)
        captureWindow(scenario).let { frame ->
            try { assertNonEmpty(frame, "dark=$dark handoff") } finally { frame.recycle() }
        }
        advanceFrames(8)
        val intermediate = captureWindow(scenario)
        try {
            assertNonEmpty(intermediate, "dark=$dark intermediate")
            advanceFrames(SettleFrames)
            assertEquals(dark, runBlocking { settings.forceDarkFlow.first() })
            assertEquals(false, runBlocking { settings.followSystemDarkFlow.first() })
            scenario.onActivity { assertSame(originalActivity, it) }
            val settled = captureWindow(scenario)
            try {
                assertNonEmpty(settled, "dark=$dark settled")
                assertTrue("揭示的中间帧与末帧必须不同，不能直接跳过动画",
                    changedPixelFraction(intermediate, settled) > 0.005)
            } finally {
                settled.recycle()
            }
        } finally {
            intermediate.recycle()
        }
    }

    private fun assertTabsRemainUsable(scenario: ActivityScenario<MainActivity>, settings: String, library: String) {
        val settingsFrame = captureWindow(scenario)
        try {
            navigationTab(library).performClick()
            advanceFrames(SettleFrames)
            navigationTab(library).assertIsSelected()
            val libraryFrame = captureWindow(scenario)
            try {
                assertNonEmpty(libraryFrame, "library after reveal")
                assertTrue("切换标签后画面必须改变，不能被旧主题快照覆盖",
                    changedPixelFraction(settingsFrame, libraryFrame) > 0.01)
            } finally {
                libraryFrame.recycle()
            }
            navigationTab(settings).performClick()
            advanceFrames(SettleFrames)
            navigationTab(settings).assertIsSelected()
        } finally {
            settingsFrame.recycle()
        }
    }

    private fun navigationTab(label: String): SemanticsNodeInteraction = composeRule.onNode(
        hasClickAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected) and
            hasAnyDescendant(hasContentDescription(label)),
        useUnmergedTree = true
    )

    private fun awaitLoadedBackground(scenario: ActivityScenario<MainActivity>, noImage: Bitmap) {
        composeRule.waitUntil(UiTimeoutMs) {
            advanceFrames(1)
            val frame = captureWindow(scenario)
            try { changedPixelFraction(noImage, frame) > 0.03 } finally { frame.recycle() }
        }
    }

    private fun awaitDisplayed(description: String) {
        composeRule.waitUntil(UiTimeoutMs) {
            if (!composeRule.mainClock.autoAdvance) advanceFrames(1)
            val nodes = composeRule.onAllNodesWithContentDescription(description)
            nodes.fetchSemanticsNodes().indices.any { nodes[it].isDisplayed() }
        }
    }

    private fun advanceFrames(count: Int) {
        repeat(count) { composeRule.mainClock.advanceTimeByFrame() }
        composeRule.waitForIdle()
    }

    private fun captureWindow(scenario: ActivityScenario<MainActivity>): Bitmap {
        check(Looper.myLooper() != Looper.getMainLooper())
        val committed = CountDownLatch(1)
        var observer: ViewTreeObserver? = null
        var callback: Runnable? = null
        var registeredAt = 0L
        var width = 0
        var height = 0
        lateinit var window: Window
        val drawingEnabled = HardwareRendererCompat.isDrawingEnabled()
        try {
            if (!drawingEnabled) HardwareRendererCompat.setDrawingEnabled(true)
            scenario.onActivity { activity ->
                window = activity.window
                val decor = window.decorView
                check(decor.isAttachedToWindow && decor.isHardwareAccelerated)
                width = decor.width
                height = decor.height
                check(width > 0 && height > 0)
                val registeredObserver = decor.viewTreeObserver
                val frameCallback = Runnable { committed.countDown() }
                observer = registeredObserver
                callback = frameCallback
                registeredObserver.registerFrameCommitCallback(frameCallback)
                registeredAt = System.nanoTime()
                // 原生窗口重绘不会推进冻结的 Compose 动画时钟
                decor.invalidate()
            }
            val remaining = TimeUnit.SECONDS.toNanos(5) - (System.nanoTime() - registeredAt)
            assertTrue("主题窗口未提交实际渲染帧", committed.await(remaining.coerceAtLeast(0L), TimeUnit.NANOSECONDS))
            return copyWindow(window, width, height)
        } finally {
            try {
                instrumentation.runOnMainSync {
                    val registeredObserver = observer
                    val frameCallback = callback
                    if (registeredObserver != null && registeredObserver.isAlive && frameCallback != null) {
                        registeredObserver.unregisterFrameCommitCallback(frameCallback)
                    }
                }
            } finally {
                if (!drawingEnabled) HardwareRendererCompat.setDrawingEnabled(false)
            }
        }
    }

    private fun copyWindow(window: Window, width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val finished = CountDownLatch(1)
        val ownership = Any()
        var abandoned = false
        var completed = false
        var copyResult = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(window, bitmap, { result ->
                synchronized(ownership) {
                    copyResult = result
                    completed = true
                    if (abandoned) bitmap.recycle()
                }
                finished.countDown()
            }, Handler(Looper.getMainLooper()))
        } catch (failure: Throwable) {
            bitmap.recycle()
            throw failure
        }
        try {
            assertTrue("主题窗口 PixelCopy 超时", finished.await(1, TimeUnit.SECONDS))
            assertEquals("主题窗口 PixelCopy 失败", PixelCopy.SUCCESS, copyResult)
            return bitmap
        } catch (failure: Throwable) {
            synchronized(ownership) {
                abandoned = true
                if (completed) bitmap.recycle()
            }
            throw failure
        }
    }

    private fun assertNonEmpty(bitmap: Bitmap, stage: String) {
        assertTrue("窗口截图尺寸无效: $stage", bitmap.width > 0 && bitmap.height > 0)
        val samples = samplePixels(bitmap)
        assertTrue("窗口截图缺少不透明内容: $stage", samples.count { Color.alpha(it) >= 250 } > samples.size * 0.95)
        val range = (0..16 step 8).maxOf { shift ->
            samples.maxOf { (it shr shift) and 255 } - samples.minOf { (it shr shift) and 255 }
        }
        assertTrue("窗口截图为空白或单色: $stage", range > 24)
    }

    private fun changedPixelFraction(first: Bitmap, second: Bitmap): Double {
        assertEquals(first.width, second.width)
        assertEquals(first.height, second.height)
        val before = samplePixels(first)
        val after = samplePixels(second)
        return before.indices.count { index ->
            maxOf(abs(Color.red(before[index]) - Color.red(after[index])),
                abs(Color.green(before[index]) - Color.green(after[index])),
                abs(Color.blue(before[index]) - Color.blue(after[index]))) > 24
        }.toDouble() / before.size
    }

    private fun samplePixels(bitmap: Bitmap): List<Int> = buildList {
        for (y in 2 until 18) for (x in 1 until 19) {
            add(bitmap.getPixel(bitmap.width * x / 20, bitmap.height * y / 20))
        }
    }

    private fun createBackground(directory: File): File {
        val bitmap = Bitmap.createBitmap(240, 360, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
                bitmap.setPixel(x, y, when ((x / 60 + y / 90) % 3) {
                    0 -> Color.rgb(232, 80, 64)
                    1 -> Color.rgb(48, 120, 224)
                    else -> Color.rgb(240, 192, 48)
                })
            }
            return File(directory, "background.png").also { file ->
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class SavedCache(val name: String, val values: Map<String, Any>) {
        fun restore(context: Context) {
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            values.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, stringSet(value))
                    else -> error("不支持的设置镜像类型: $name/$key")
                }
            }
            assertTrue("未恢复设置镜像: $name", editor.commit())
        }

        companion object {
            fun read(context: Context, name: String): SavedCache {
                val preferences: SharedPreferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                return SavedCache(name, preferences.all.mapValues { (key, value) ->
                    when (value) {
                        is Boolean, is String, is Int, is Long, is Float -> checkNotNull(value)
                        is Set<*> -> stringSet(value)
                        else -> error("不支持的设置镜像类型: $name/$key")
                    }
                })
            }

            private fun stringSet(value: Set<*>): Set<String> = value.map { entry ->
                check(entry is String)
                entry
            }.toSet()
        }
    }

    private companion object {
        const val UiTimeoutMs = 20_000L
        const val SettleFrames = 80
        val CacheNames = listOf("startup_progress_cache", "theme_snapshot_cache",
            "playback_snapshot_cache", "bootstrap_settings_snapshot")
    }
}
