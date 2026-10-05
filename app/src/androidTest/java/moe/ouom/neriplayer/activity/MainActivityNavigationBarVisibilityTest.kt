package moe.ouom.neriplayer.activity

import android.app.AlertDialog
import android.content.res.Configuration
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.startup.safemode.SafeModeManager
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.testutil.UiFailureDiagnostics
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityNavigationBarVisibilityTest {
    @Test
    fun navigationPolicySurvivesDialogFocusResumeAndRecreation() = withOnboardingActivity { scenario, hidden ->
        var dialog: AlertDialog? = null
        try {
            scenario.onActivity { activity ->
                dialog = AlertDialog.Builder(activity).setTitle("Navigation focus fixture")
                    .setPositiveButton("Close", null).create().also(AlertDialog::show)
            }
            awaitActivity("Dialog 应暂时取得窗口焦点", scenario) { !it.hasWindowFocus() }
        } finally {
            scenario.onActivity { dialog?.dismiss() }
        }
        awaitNavigationPolicy(scenario, hidden)
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        awaitNavigationPolicy(scenario, hidden)
        scenario.recreate()
        awaitNavigationPolicy(scenario, hidden)
    }

    @Test
    fun navigationPolicyReturnsAfterKeyboardDismissal() = withOnboardingActivity { scenario, hidden ->
        var editor: EditText? = null
        try {
            scenario.onActivity { activity ->
                val content = activity.findViewById<FrameLayout>(android.R.id.content)
                editor = EditText(activity).apply {
                    hint = "Navigation keyboard fixture"
                    setSingleLine()
                    content.addView(this, FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER
                    ))
                    requestFocus()
                    WindowInsetsControllerCompat(activity.window, this).show(WindowInsetsCompat.Type.ime())
                }
            }
            awaitActivity("应打开真实 IME", scenario) { activity ->
                val imeVisible = ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
                imeVisible && editor?.let { it.isFocused &&
                    activity.getSystemService(InputMethodManager::class.java).isActive(it) } == true
            }
            val draft = "123456"
            InstrumentationRegistry.getInstrumentation().sendStringSync(draft)
            awaitActivity("真实 IME 打开时编辑框应能接收输入", scenario) { activity ->
                editor?.text?.toString() == draft && ViewCompat.getRootWindowInsets(activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            // 请求显示导航栏，IME 期间控制权可能暂不属于 Activity，关闭后仍应恢复自身策略
            scenario.onActivity { activity ->
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .show(WindowInsetsCompat.Type.navigationBars())
            }
            scenario.onActivity { activity ->
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
            }
            awaitActivity("IME 应关闭且编辑草稿应保留", scenario) { activity ->
                ViewCompat.getRootWindowInsets(activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == false &&
                    editor?.text?.toString() == draft && editor.isFocused
            }
            awaitNavigationPolicy(scenario, hidden)
        } finally {
            scenario.onActivity { activity ->
                editor?.clearFocus()
                WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                    .hide(WindowInsetsCompat.Type.ime())
                editor?.let { (it.parent as? ViewGroup)?.removeView(it) }
            }
        }
    }

    private fun withOnboardingActivity(action: (ActivityScenario<MainActivity>, Boolean) -> Unit) {
        assumeComposeHostAvailable()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context)
        val savedDisclaimer = runBlocking { settings.disclaimerAcceptedFlow.filterNotNull().first() }
        val savedOnboarding = runBlocking { settings.startupOnboardingCompletedFlow.filterNotNull().first() }
        try {
            runBlocking {
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(false)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                UiFailureDiagnostics.onFailure("navigation-bar-policy") {
                    var hidden = false
                    scenario.onActivity { activity ->
                        val configuration = activity.resources.configuration
                        hidden = configuration.smallestScreenWidthDp >= 600 &&
                            configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                            !SafeModeManager.shouldEnterSafeMode(activity)
                    }
                    awaitNavigationPolicy(scenario, hidden)
                    action(scenario, hidden)
                }
            }
        } finally {
            runBlocking {
                settings.setDisclaimerAccepted(savedDisclaimer)
                settings.setStartupOnboardingCompleted(savedOnboarding)
            }
        }
    }

    private fun awaitNavigationPolicy(scenario: ActivityScenario<MainActivity>, hidden: Boolean) {
        awaitActivity("导航栏应遵循真实设备方向与模式", scenario) { activity ->
            val insets = ViewCompat.getRootWindowInsets(activity.window.decorView)
            activity.hasWindowFocus() && insets != null &&
                insets.isVisible(WindowInsetsCompat.Type.navigationBars()) == !hidden
        }
        scenario.onActivity { activity ->
            val insets = checkNotNull(ViewCompat.getRootWindowInsets(activity.window.decorView))
            assertTrue("状态栏应保留", insets.isVisible(WindowInsetsCompat.Type.statusBars()))
            val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            assertEquals(if (hidden) WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                else WindowInsetsControllerCompat.BEHAVIOR_DEFAULT, controller.systemBarsBehavior)
            if (hidden) {
                assertEquals("隐藏导航栏不应继续占据布局边距", Insets.NONE,
                    insets.getInsets(WindowInsetsCompat.Type.navigationBars()))
            }
        }
    }

    private fun awaitActivity(
        message: String, scenario: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean
    ) {
        val deadline = SystemClock.uptimeMillis() + 15_000L
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        while (SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString() == "android" &&
                root.findAccessibilityNodeInfosByViewId("android:id/immersive_cling_title")
                    .any { it.isVisibleToUser && it.packageName?.toString() == "android" }) {
                root.findAccessibilityNodeInfosByViewId("android:id/ok")
                    .singleOrNull { it.isVisibleToUser && it.isEnabled && it.isClickable &&
                        it.packageName?.toString() == "android" }
                    ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var ready = false
            scenario.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(25L)
        }
        assertTrue(message, false)
    }
}
