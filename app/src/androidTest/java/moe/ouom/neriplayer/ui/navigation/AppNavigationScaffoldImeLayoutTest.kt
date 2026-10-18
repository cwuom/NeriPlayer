package moe.ouom.neriplayer.ui.navigation

import android.content.res.Configuration
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavDestination
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.navigation.Destinations
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.BottomBarLayoutInsets
import moe.ouom.neriplayer.ui.screen.tab.settings.navigation.SettingsSearchField
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppNavigationScaffoldImeLayoutTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private lateinit var hostView: View
    private lateinit var focusManager: FocusManager
    private var originalCleanMotion = false
    private var originalKeyboardPreference = "not captured"
    private var contentVisible by mutableStateOf(true)
    private var observedImeVisible = false
    private var observedInsets: BottomBarLayoutInsets? = null
    private var observedMiniReserve = (-1).dp
    private var density = 1f
    private var expandCalls = 0
    private val selectedRoutes = mutableListOf<String>()

    @Before
    fun prepareHost() {
        originalCleanMotion = NeriMotionStyle.clean
        assumeTrue("原生键盘回归仅在 API30+ 模拟器运行", Build.VERSION.SDK_INT >= 30 &&
            Build.HARDWARE in setOf("ranchu", "goldfish"))
        assumeComposeHostAvailable()
        composeRule.activityRule.scenario.onActivity { it.enableEdgeToEdge() }
    }

    @After
    fun restoreHost() {
        try {
            if (!::hostView.isInitialized) return
            composeRule.runOnIdle {
                if (::focusManager.isInitialized) focusManager.clearFocus(force = true)
                WindowInsetsControllerCompat(composeRule.activity.window, hostView)
                    .hide(WindowInsetsCompat.Type.ime())
                contentVisible = false
            }
            composeRule.waitForIdle()
        } finally {
            NeriMotionStyle.clean = originalCleanMotion
        }
    }

    @Test
    fun cleanPhoneRealImeReleasesBothChromeReservesAndRestoresNativeActions() = withSoftwareKeyboard {
        assertRealImeLayout(smallestWidth = 390, baseBlurRequested = false)
    }

    @Test
    fun cleanNarrowTabletRealImeReleasesBlurredChromeReservesAndRestoresNativeActions() = withSoftwareKeyboard {
        assertRealImeLayout(smallestWidth = 800, baseBlurRequested = true)
    }

    private fun assertRealImeLayout(smallestWidth: Int, baseBlurRequested: Boolean) =
        UiFailureDiagnostics.onFailure("navigation-ime-sw$smallestWidth-blur-$baseBlurRequested") {
            render(smallestWidth, baseBlurRequested)
            awaitIme(visible = false)
            composeRule.onNodeWithTag(BottomBarTag).assertIsDisplayed()
            composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()
            val initialHost = bounds(HostTag)
            val initialContent = bounds(ContentTag)
            val initialUsable = bounds(UsableTag)
            var initialInsets: BottomBarLayoutInsets? = null
            var initialMiniReserve = 0.dp
            composeRule.runOnIdle {
                initialInsets = checkNotNull(observedInsets)
                initialMiniReserve = observedMiniReserve
                assertTrue("有歌且无键盘时必须为播放浮层预留空间", observedMiniReserve > 0.dp)
            }
            assertTrue("夹具必须真实保留底栏和迷你播放器空间", initialHost.bottom - initialUsable.bottom > 40f)

            composeRule.onNode(hasSetTextAction()).performNativeClick().assertIsFocused()
            composeRule.runOnIdle {
                WindowInsetsControllerCompat(composeRule.activity.window, hostView)
                    .show(WindowInsetsCompat.Type.ime())
            }
            awaitIme(visible = true)
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                bounds(HostTag).height < initialHost.height - density
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(BottomBarTag).assertIsNotDisplayed()
            composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsNotDisplayed()
            composeRule.runOnIdle {
                val insets = checkNotNull(observedInsets)
                assertEquals("IME 隐藏底栏时不能继续占内容高度", 0.dp, insets.navContentBottomPadding)
                assertEquals("IME 隐藏播放浮层时页面 reserve 必须归零", 0.dp, insets.screenBottomInset)
                assertEquals(0.dp, insets.miniPlayerBottomPadding)
                assertEquals("页面实际接收的 Local reserve 必须归零", 0.dp, observedMiniReserve)
            }
            val imeHost = bounds(HostTag)
            assertTrue("真实软键盘必须改变可用布局高度", imeHost.height < initialHost.height)
            assertEquals("键盘上方不能保留额外底栏高度", imeHost.bottom, bounds(ContentTag).bottom, 1f)
            assertEquals("实际页面底边必须到达键盘上方的可用底边", imeHost.bottom, bounds(UsableTag).bottom, 1f)

            composeRule.runOnIdle {
                focusManager.clearFocus(force = true)
                WindowInsetsControllerCompat(composeRule.activity.window, hostView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            awaitIme(visible = false)
            composeRule.waitUntil(timeoutMillis = 15_000L) {
                kotlin.math.abs(bounds(HostTag).height - initialHost.height) <= density
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithTag(BottomBarTag).assertIsDisplayed()
            composeRule.onNodeWithTag(MiniPlayerTag, useUnmergedTree = true).assertIsDisplayed()
            composeRule.runOnIdle {
                assertEquals(initialInsets, observedInsets)
                assertEquals(initialMiniReserve, observedMiniReserve)
            }
            assertSameBounds(initialHost, bounds(HostTag))
            assertSameBounds(initialContent, bounds(ContentTag))
            assertSameBounds(initialUsable, bounds(UsableTag))
            composeRule.onNodeWithText(SongTitle, useUnmergedTree = true).performNativeClick()
            composeRule.runOnIdle { assertEquals(1, expandCalls) }
            composeRule.onNodeWithText(composeRule.activity.getString(Destinations.Library.labelResId))
                .performNativeClick()
            composeRule.runOnIdle { assertEquals(listOf(Destinations.Library.route), selectedRoutes) }
        }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    private fun render(smallestWidth: Int, baseBlurRequested: Boolean) {
        composeRule.setContent {
            if (contentVisible) {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    smallestScreenWidthDp = smallestWidth
                }
                val view = LocalView.current
                val focus = LocalFocusManager.current
                val imeVisible = WindowInsets.isImeVisible
                val currentDensity = LocalDensity.current
                SideEffect {
                    hostView = view
                    focusManager = focus
                    observedImeVisible = imeVisible
                    density = currentDensity.density
                }
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    NeriTheme(followSystemDark = false, forceDark = false, dynamicColor = false,
                        seedColorHex = "6750A4", uiStyle = UiStyle.Clean, animateColors = false) {
                        Box(Modifier.fillMaxSize()
                            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
                            .imePadding(), contentAlignment = Alignment.Center) {
                            // 限定为平板窄窗口的底栏分支，真实键盘和系统 inset 仍来自宿主
                            Box(Modifier.fillMaxHeight().widthIn(max = 600.dp).fillMaxWidth().testTag(HostTag)) {
                                AppNavigationScaffold(
                                    bottomBar = AppBottomBarPresentation(
                                        listOf(Destinations.Home to Icons.Outlined.Home,
                                            Destinations.Library to Icons.Outlined.LibraryMusic),
                                        NavDestination("fixture").apply { route = Destinations.Home.route },
                                        false, false, true, null
                                    ),
                                    miniPlayer = AppMiniPlayerPresentation(
                                        SongItem(17L, SongTitle, "Fixture artist", "", 0L, 120_000L, null),
                                        null, null, null, null, false
                                    ),
                                    baseBlurRequested = baseBlurRequested,
                                    snackbarHostState = remember { SnackbarHostState() },
                                    onMainTabSelected = selectedRoutes::add,
                                    onExpandNowPlaying = { expandCalls++ }
                                ) { insets ->
                                    val miniReserve = LocalMiniPlayerHeight.current
                                    SideEffect {
                                        observedInsets = insets
                                        observedMiniReserve = miniReserve
                                    }
                                    Box(Modifier.fillMaxSize().testTag(ContentTag)) {
                                        Box(Modifier.fillMaxSize().padding(bottom = miniReserve)) {
                                            Column(Modifier.fillMaxSize().testTag(UsableTag)) {
                                                var query by remember { mutableStateOf("IME fixture") }
                                                SettingsSearchField(query, onQueryChange = { query = it })
                                                Text("Scrollable page content")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        composeRule.waitForIdle()
    }

    private fun awaitIme(visible: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var lastState = "not sampled"
        try {
            composeRule.waitUntil(conditionDescription = "原生及 Compose IME 状态必须一致: visible=$visible",
                timeoutMillis = 15_000L) {
                var ready = false
                instrumentation.runOnMainSync {
                    val insets = ViewCompat.getRootWindowInsets(hostView)
                    val input = hostView.context.getSystemService(InputMethodManager::class.java)
                    val nativeVisible = insets?.isVisible(WindowInsetsCompat.Type.ime())
                    val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom
                    val windowFocus = hostView.hasWindowFocus()
                    val touchMode = hostView.isInTouchMode
                    val active = input.isActive(hostView)
                    val acceptingText = input.isAcceptingText
                    val configuration = hostView.resources.configuration
                    lastState = "nativeVisible=$nativeVisible, bottom=$bottom, composeVisible=$observedImeVisible, " +
                        "windowFocus=$windowFocus, touchMode=$touchMode, active=$active, acceptingText=$acceptingText, " +
                        "keyboard=${configuration.keyboard}, hardKeyboardHidden=${configuration.hardKeyboardHidden}"
                    ready = windowFocus && nativeVisible == visible &&
                        (visible || bottom == 0) &&
                        (!visible || touchMode && bottom != null && bottom > 0 && active && acceptingText) &&
                        observedImeVisible == visible
                }
                ready
            }
        } catch (failure: ComposeTimeoutException) {
            runCatching { recordImeWindowDiagnostics() }.onFailure(failure::addSuppressed)
            throw AssertionError("真实 IME 前置未就绪: expectedVisible=$visible, " +
                "originalKeyboardPreference=$originalKeyboardPreference, $lastState", failure)
        }
    }

    private fun recordImeWindowDiagnostics() {
        val targetPackage = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val commands = listOf(
            "input-method" to "dumpsys -t 3 input_method",
            "target-windows" to "dumpsys -t 3 window windows $targetPackage",
            "ime-windows" to "dumpsys -t 3 window windows InputMethod"
        )
        for ((section, command) in commands) {
            runCatching {
                val output = shell(command)
                // 输入法诊断只保留窗口、可见性和布局状态，不记录编辑器文本或账号信息
                val diagnostic = if (section == "input-method") {
                    output.lineSequence().filter { InputMethodStateFields.containsMatchIn(it) }
                        .joinToString("\n").ifEmpty { "no matching input-method state fields" }
                } else output
                for ((index, chunk) in diagnostic.take(32_768).chunked(1_000).withIndex()) {
                    Log.e("UiFailureDiagnostics", "navigation-ime [$section:$index] $chunk")
                }
            }.onFailure {
                Log.e("UiFailureDiagnostics", "navigation-ime [$section] unavailable", it)
            }
        }
    }

    private fun withSoftwareKeyboard(test: () -> Unit) {
        val previous = shell("settings get secure show_ime_with_hard_keyboard")
        check(previous in setOf("null", "0", "1")) { "未知的软键盘设置，不能安全恢复: $previous" }
        originalKeyboardPreference = previous
        try {
            // 这里只验证真实键盘出现后的布局，模拟器的硬件键盘策略不属于被测行为
            shell("settings put secure show_ime_with_hard_keyboard 1")
            assertEquals("软键盘测试前置必须生效，原值=$previous", "1",
                shell("settings get secure show_ime_with_hard_keyboard"))
            test()
        } finally {
            if (previous == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $previous")
            assertEquals("软键盘设置必须恢复原值", previous,
                shell("settings get secure show_ime_with_hard_keyboard"))
        }
    }

    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
    ).bufferedReader().use { it.readText().trim() }

    private fun bounds(tag: String): Rect = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
        .fetchSemanticsNode().boundsInRoot

    private fun assertSameBounds(expected: Rect, actual: Rect) {
        val tolerancePx = density
        assertEquals(expected.left, actual.left, tolerancePx)
        assertEquals(expected.top, actual.top, tolerancePx)
        assertEquals(expected.right, actual.right, tolerancePx)
        assertEquals(expected.bottom, actual.bottom, tolerancePx)
    }

    private companion object {
        val InputMethodStateFields = Regex("\\b(mCurMethodId|mCurFocusedWindow|mCurFocusedWindowSoftInputMode|" +
            "mCurClient|mCurTokenDisplayId|mInputShown|mShowInputRequested|mShowRequested|" +
            "mShowExplicitlyRequested|mShowForced|mImeWindowVis|mWindowVisible|mDecorViewVisible|" +
            "mIsInputViewShown|mInputViewShown|mIsFullscreen|mFullscreenApplied|mConfiguration|" +
            "mInputFrame|mCandidatesFrame|contentTopInsets|visibleTopInsets)\\b")
        const val HostTag = "ime-scaffold-host"
        const val ContentTag = "ime-scaffold-content"
        const val UsableTag = "ime-scaffold-usable-content"
        const val BottomBarTag = "appBottomNavigation"
        const val MiniPlayerTag = "appMiniPlayer"
        const val SongTitle = "IME mini fixture"
    }
}
