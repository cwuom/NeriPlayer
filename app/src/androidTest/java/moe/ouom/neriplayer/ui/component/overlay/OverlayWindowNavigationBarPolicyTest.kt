package moe.ouom.neriplayer.ui.component.overlay

import android.app.AlertDialog
import android.graphics.Bitmap
import android.util.Log
import android.view.View
import android.view.Window
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.testutil.sendNativeSoftKeyboardText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(AndroidJUnit4::class)
class OverlayWindowNavigationBarPolicyTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private enum class Overlay { Sheet, Alert }

    private val visibleOverlay = mutableStateOf<Overlay?>(null)
    private val draft = mutableStateOf("")
    private val dialogWindow = AtomicReference<Window?>(null)
    private val dialogView = AtomicReference<View?>(null)
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun requireUnlockedHost() {
        assumeComposeHostAvailable()
    }

    @After
    fun closeOwnedWindowAndKeyboard() {
        instrumentation.runOnMainSync {
            dialogWindow.get()?.let { window ->
                WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
            }
            visibleOverlay.value = null
            val window = composeRule.activity.window
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
                hide(WindowInsetsCompat.Type.ime())
                show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    @Test
    fun immersiveSheetKeepsItsOwnNavigationHiddenThroughFocusAndReopen() =
        UiFailureDiagnostics.onFailure("overlay-sheet-navigation") {
            render(Overlay.Sheet, mutableStateOf(true))
            val sheetWindow = awaitDialogWindow()
            awaitNavigationPolicy(sheetWindow, hidden = true, stage = "sheet-initial")
            capture("sheet-open-hidden")

            composeRule.runOnIdle {
                WindowInsetsControllerCompat(sheetWindow, sheetWindow.decorView)
                    .show(WindowInsetsCompat.Type.navigationBars())
            }
            awaitWindowInsets(sheetWindow, "焦点恢复前先使导航栏可见") {
                it.isVisible(WindowInsetsCompat.Type.navigationBars())
            }
            var childDialog: AlertDialog? = null
            try {
                composeRule.activityRule.scenario.onActivity { activity ->
                    childDialog = AlertDialog.Builder(activity).setTitle("Overlay focus fixture")
                        .setPositiveButton("Close", null).create().also(AlertDialog::show)
                }
                composeRule.waitUntil(5_000) {
                    var hasLostFocus = false
                    instrumentation.runOnMainSync { hasLostFocus = !sheetWindow.decorView.hasWindowFocus() }
                    hasLostFocus
                }
            } finally {
                instrumentation.runOnMainSync { childDialog?.dismiss() }
            }
            awaitNavigationPolicy(sheetWindow, hidden = true, stage = "sheet-focus-restored")
            closeOverlay(hostHidden = true)
            composeRule.runOnIdle { visibleOverlay.value = Overlay.Sheet }
            val reopened = awaitDialogWindow()
            assertNotSame("重新打开应创建新的真实 Dialog window", sheetWindow, reopened)
            awaitNavigationPolicy(reopened, hidden = true, stage = "sheet-reopened")
            closeOverlay(hostHidden = true)
        }

    @Test
    fun alertDialogUpdatesThePolicyWithoutReplacingItsWindow() =
        UiFailureDiagnostics.onFailure("overlay-alert-policy-change") {
            val hidden = mutableStateOf(false)
            render(Overlay.Alert, hidden)
            val window = awaitDialogWindow()
            awaitNavigationPolicy(window, hidden = false, stage = "alert-initial")
            composeRule.runOnIdle {
                hidden.value = true
                applyHostPolicy(hidden = true)
            }
            awaitNavigationPolicy(window, hidden = true, stage = "alert-policy-true")
            assertSame(window, dialogWindow.get())
            composeRule.runOnIdle {
                hidden.value = false
                applyHostPolicy(hidden = false)
            }
            awaitNavigationPolicy(window, hidden = false, stage = "alert-policy-false")
            assertSame(window, dialogWindow.get())
            closeOverlay(hostHidden = false)
        }

    @Test
    fun defaultPolicyKeepsSheetAndAlertNavigationVisible() =
        UiFailureDiagnostics.onFailure("overlay-default-navigation") {
            render(Overlay.Sheet)
            awaitNavigationPolicy(awaitDialogWindow(), hidden = false, stage = "default-sheet")
            closeOverlay(hostHidden = false)
            composeRule.runOnIdle { visibleOverlay.value = Overlay.Alert }
            awaitNavigationPolicy(awaitDialogWindow(), hidden = false, stage = "default-alert")
            capture("default-alert-visible")
            closeOverlay(hostHidden = false)
        }

    @Test
    fun sheetKeyboardDismissalPreservesDraftAndRestoresNavigationPolicy() =
        verifyKeyboardDismissal(Overlay.Sheet)

    @Test
    fun alertKeyboardDismissalPreservesDraftAndRestoresNavigationPolicy() =
        verifyKeyboardDismissal(Overlay.Alert)

    private fun verifyKeyboardDismissal(overlay: Overlay) =
        UiFailureDiagnostics.onFailure("overlay-${overlay.name.lowercase()}-keyboard") {
            render(overlay, mutableStateOf(true), editable = true)
            val window = awaitDialogWindow()
            awaitNavigationPolicy(window, hidden = true, stage = "${overlay.name.lowercase()}-before-ime")
            try {
                composeRule.onNodeWithTag(INPUT).performNativeClick()
                composeRule.runOnIdle {
                    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.ime())
                }
                awaitWindowInsets(window, "弹窗必须打开真实 IME") { insets ->
                    insets.isVisible(WindowInsetsCompat.Type.ime()) && dialogView.get()?.let { view ->
                        val input = composeRule.activity.getSystemService(InputMethodManager::class.java)
                        view.isInTouchMode && view.hasWindowFocus() && input.isActive(view) && input.isAcceptingText
                    } == true
                }
                composeRule.onNodeWithTag(INPUT).assertIsFocused()
                sendNativeSoftKeyboardText(DRAFT)
                UiFailureDiagnostics.onFailure("overlay-${overlay.name.lowercase()}-ime-input") {
                    composeRule.waitUntil(conditionDescription = "原生软键盘输入应写入弹窗草稿", timeoutMillis = 5_000) {
                        draft.value == DRAFT
                    }
                }
                composeRule.onNodeWithTag(INPUT).assertTextEquals(DRAFT).assertIsFocused()
                composeRule.runOnIdle {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        show(WindowInsetsCompat.Type.navigationBars())
                        hide(WindowInsetsCompat.Type.ime())
                    }
                }
                awaitWindowInsets(window, "关闭真实 IME 后草稿应保留") {
                    !it.isVisible(WindowInsetsCompat.Type.ime()) && draft.value == DRAFT
                }
                awaitNavigationPolicy(window, hidden = true, stage = "${overlay.name.lowercase()}-ime-dismissed")
                composeRule.onNodeWithTag(INPUT).assertTextEquals(DRAFT).assertIsFocused()
                capture("${overlay.name.lowercase()}-ime-dismissed")
            } finally {
                composeRule.runOnIdle {
                    WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
                }
            }
            closeOverlay(hostHidden = true)
        }

    private fun render(overlay: Overlay, hidden: MutableState<Boolean>? = null, editable: Boolean = false) {
        composeRule.activityRule.scenario.onActivity {
            WindowCompat.setDecorFitsSystemWindows(it.window, false)
            applyHostPolicy(hidden?.value == true)
        }
        visibleOverlay.value = overlay
        composeRule.setContent {
            MaterialTheme {
                if (hidden != null) {
                    CompositionLocalProvider(LocalOverlayNavigationBarHidden provides hidden.value) {
                        FixtureContent(editable)
                    }
                } else FixtureContent(editable)
            }
        }
        composeRule.waitForIdle()
    }

    @Composable
    private fun FixtureContent(editable: Boolean) {
        Box(Modifier.fillMaxSize()) { Text("Overlay Activity fixture") }
        when (visibleOverlay.value) {
            Overlay.Sheet -> DensityScaledModalBottomSheet(
                onDismissRequest = { visibleOverlay.value = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                sheetGesturesEnabled = false
            ) {
                DialogWindowProbe()
                Column(Modifier.fillMaxWidth().padding(24.dp)) {
                    Text("Overlay sheet fixture")
                    if (editable) FixtureEditor()
                    TextButton(onClick = { visibleOverlay.value = null }, modifier = Modifier.testTag(CLOSE)) { Text("Close") }
                }
            }
            Overlay.Alert -> DensityScaledAlertDialog(
                onDismissRequest = { visibleOverlay.value = null },
                title = { Text("Overlay alert fixture") },
                text = {
                    DialogWindowProbe()
                    if (editable) FixtureEditor() else Text("Synthetic dialog content")
                },
                confirmButton = {
                    TextButton(onClick = { visibleOverlay.value = null }, modifier = Modifier.testTag(CLOSE)) { Text("Close") }
                }
            )
            null -> Unit
        }
    }

    @Composable
    private fun FixtureEditor() {
        OutlinedTextField(
            value = draft.value,
            onValueChange = { draft.value = it },
            modifier = Modifier.fillMaxWidth().testTag(INPUT),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
        )
    }

    @Composable
    private fun DialogWindowProbe() {
        val view = LocalView.current
        DisposableEffect(view) {
            var ancestor: View? = view
            while (ancestor != null && ancestor !is DialogWindowProvider) ancestor = ancestor.parent as? View
            val window = checkNotNull((ancestor as? DialogWindowProvider)?.window)
            assertNotSame("应检查弹窗自己的 Window", composeRule.activity.window, window)
            dialogWindow.set(window)
            dialogView.set(view)
            onDispose {
                dialogWindow.compareAndSet(window, null)
                dialogView.compareAndSet(view, null)
            }
        }
    }

    private fun awaitDialogWindow(): Window {
        composeRule.waitUntil(5_000) { dialogWindow.get() != null }
        return checkNotNull(dialogWindow.get())
    }

    private fun closeOverlay(hostHidden: Boolean) {
        composeRule.onNodeWithTag(CLOSE).performClick()
        composeRule.waitUntil(5_000) { dialogWindow.get() == null }
        awaitNavigationPolicy(composeRule.activity.window, hostHidden, stage = "host-after-close")
    }

    private fun applyHostPolicy(hidden: Boolean) {
        val window = composeRule.activity.window
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.statusBars())
            systemBarsBehavior = if (hidden) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            if (hidden) hide(WindowInsetsCompat.Type.navigationBars())
            else show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun awaitNavigationPolicy(window: Window, hidden: Boolean, stage: String) {
        val expectedBehavior = if (hidden) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
        instrumentation.runOnMainSync {
            Log.i("OverlayWindowNavigation", "$stage awaiting hidden=$hidden behavior=$expectedBehavior: ${navigationPolicySnapshot(window)}")
        }
        try {
            awaitWindowInsets(window, "$stage: 当前真实 Window 的导航栏应 ${if (hidden) "隐藏" else "可见"}且窗口策略完成更新") {
                it.isVisible(WindowInsetsCompat.Type.navigationBars()) == !hidden &&
                    it.isVisible(WindowInsetsCompat.Type.statusBars()) &&
                    WindowInsetsControllerCompat(window, window.decorView).systemBarsBehavior == expectedBehavior &&
                    (!hidden || it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom == 0)
            }
        } catch (failure: Throwable) {
            runCatching {
                instrumentation.runOnMainSync {
                    Log.e("OverlayWindowNavigation", "$stage failed hidden=$hidden behavior=$expectedBehavior: ${navigationPolicySnapshot(window)}", failure)
                }
            }
            throw failure
        }
        instrumentation.runOnMainSync {
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(window.decorView))
            assertTrue("$stage: 当前 Window 必须持有焦点", window.decorView.hasWindowFocus())
            assertEquals("$stage: 导航栏可见性", !hidden, insets.isVisible(WindowInsetsCompat.Type.navigationBars()))
            assertTrue("$stage: 策略不能隐藏状态栏", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            assertEquals(
                "$stage: 当前 Window 的 systemBarsBehavior",
                expectedBehavior,
                WindowInsetsControllerCompat(window, window.decorView).systemBarsBehavior
            )
            if (hidden) assertEquals("$stage: 隐藏导航栏不能保留底部 inset", 0, insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            Log.i("OverlayWindowNavigation", "$stage complete hidden=$hidden behavior=$expectedBehavior: ${navigationPolicySnapshot(window)}")
        }
    }

    private fun navigationPolicySnapshot(window: Window): String {
        val decorView = window.decorView
        val insets = ViewCompat.getRootWindowInsets(decorView)
        return "window=${System.identityHashCode(window)} attached=${decorView.isAttachedToWindow} " +
            "focused=${decorView.hasWindowFocus()} navigationVisible=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())} " +
            "statusVisible=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())} " +
            "navigationBottom=${insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom} " +
            "imeVisible=${insets?.isVisible(WindowInsetsCompat.Type.ime())} " +
            "behavior=${WindowInsetsControllerCompat(window, decorView).systemBarsBehavior}"
    }

    private fun awaitWindowInsets(window: Window, description: String, matches: (WindowInsetsCompat) -> Boolean) {
        composeRule.waitUntil(conditionDescription = description, timeoutMillis = 15_000) {
            dismissImmersivePrompt()
            var matched = false
            instrumentation.runOnMainSync {
                val insets = ViewCompat.getRootWindowInsets(window.decorView)
                matched = window.decorView.hasWindowFocus() && insets != null && matches(insets)
            }
            matched
        }
    }

    private fun dismissImmersivePrompt() {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return
        if (root.packageName?.toString() == "android" &&
            root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title").any { it.isVisibleToUser }
        ) {
            root.findAccessibilityNodeInfosByViewId("android:id/ok")
                .singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable }
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    private fun capture(stage: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(60)?.takeIf(String::isNotBlank)
            ?: "overlay".takeIf { arguments.getString("captureUi").toBoolean() } ?: return
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val output = File(instrumentation.targetContext.cacheDir, "$prefix-$stage.png")
            output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("OverlayWindowNavigation", "capture=${output.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val INPUT = "overlayWindowInput"
        const val CLOSE = "overlayWindowClose"
        const val DRAFT = "24680"
    }
}
