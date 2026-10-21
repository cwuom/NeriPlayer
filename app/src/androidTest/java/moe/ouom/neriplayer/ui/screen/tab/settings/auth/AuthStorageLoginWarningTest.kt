package moe.ouom.neriplayer.ui.screen.tab.settings.auth

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testing.TestComposeActivity
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.testutil.performNativeClick
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthStorageLoginWarningTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val originalMotionStyle = NeriMotionStyle.clean
    private val visible = mutableStateOf(true)
    private val persistent = mutableStateOf(true)
    private val ready = mutableStateOf(true)
    private val inlineMessage = mutableStateOf<String?>(null)
    private val savedCookies = mutableListOf<String>()
    private var browserCalls = 0
    private var contentInstalled = false
    private val browserAction get() = string(CoreCommonR.string.login_start_bili_qr)
    private val cookieLabel get() = string(CoreCommonR.string.login_paste_bili_cookie_hint)

    @Before
    fun verifyHost() {
        assumeComposeHostAvailable()
    }

    @After
    fun restoreFixture() {
        try {
            if (contentInstalled) {
                composeRule.runOnIdle { visible.value = false }
                composeRule.waitForIdle()
            }
        } finally {
            NeriMotionStyle.clean = originalMotionStyle
        }
    }

    @Test
    fun normalStorageStartsBrowserWithoutWarningAndConsumesTheActionOnce() {
        render(requiresWarning = false)
        action(browserAction).performClick()
        composeRule.runOnIdle { assertEquals(1, browserCalls) }
        action(browserAction).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_title)).assertDoesNotExist()
    }

    @Test
    fun normalStorageImportsCookieWithoutWarning() {
        render(requiresWarning = false, initialTab = 1)
        enterCookie()
        action(string(CoreCommonR.string.login_save_cookie)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(SYNTHETIC_COOKIE), savedCookies) }
        action(string(CoreCommonR.string.login_save_cookie)).assertIsNotEnabled()
    }

    @Test
    fun cleanLightRequiresConfirmationAndSupportsCancellation() =
        assertBrowserWarning(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkRequiresConfirmationAndSupportsCancellation() =
        assertBrowserWarning(UiStyle.Clean, dark = true)

    @Test
    fun materialRequiresConfirmationAndSupportsCancellation() =
        assertBrowserWarning(UiStyle.Material, dark = false)

    @Test
    fun compatibilityCookieImportWaitsForConfirmation() {
        render(requiresWarning = true, initialTab = 1)
        enterCookie()
        action(string(CoreCommonR.string.login_save_cookie)).performNativeClick()
        awaitWarning()
        composeRule.runOnIdle { assertTrue(savedCookies.isEmpty()) }
        action(string(CoreCommonR.string.action_cancel)).performNativeClick()
        composeRule.runOnIdle { assertTrue(savedCookies.isEmpty()) }
        action(string(CoreCommonR.string.login_save_cookie)).performNativeClick()
        awaitWarning()
        action(string(CoreCommonR.string.auth_storage_compatibility_continue)).performNativeClick()
        composeRule.runOnIdle { assertEquals(listOf(SYNTHETIC_COOKIE), savedCookies) }
        action(string(CoreCommonR.string.login_save_cookie)).assertIsNotEnabled()
    }

    @Test
    fun unavailableStorageBlocksBrowserAndCookieImport() {
        persistent.value = false
        render(requiresWarning = true)
        action(browserAction).performNativeClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_unavailable_message)).assertIsDisplayed()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_continue)).assertDoesNotExist()
        action(string(CoreCommonR.string.action_ok)).performNativeClick()
        action(string(CoreCommonR.string.login_paste_cookie)).performClick()
        enterCookie()
        action(string(CoreCommonR.string.login_save_cookie)).performNativeClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_unavailable_message)).assertIsDisplayed()
        composeRule.runOnIdle {
            assertEquals(0, browserCalls)
            assertTrue(savedCookies.isEmpty())
        }
    }

    @Test
    fun storageFailureWhileWarningIsOpenPreventsContinuation() {
        render(requiresWarning = true)
        action(browserAction).performClick()
        awaitWarning()
        composeRule.runOnIdle { persistent.value = false }
        action(string(CoreCommonR.string.auth_storage_compatibility_continue)).performNativeClick()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_unavailable_message)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, browserCalls) }
    }

    @Test
    fun storageInitializationDisablesBothLoginMethods() {
        ready.value = false
        render(requiresWarning = false)
        action(browserAction).assertIsNotEnabled()
        action(string(CoreCommonR.string.login_paste_cookie)).performClick()
        action(string(CoreCommonR.string.login_save_cookie)).assertIsNotEnabled()
    }

    @Test
    fun failedImportAllowsAnExplicitRetry() {
        render(requiresWarning = false, initialTab = 1, importFailureMessage = "Synthetic import failure")
        enterCookie()
        action(string(CoreCommonR.string.login_save_cookie)).performClick()
        composeRule.waitForIdle()
        action(string(CoreCommonR.string.login_save_cookie)).performClick()
        composeRule.runOnIdle { assertEquals(listOf(SYNTHETIC_COOKIE, SYNTHETIC_COOKIE), savedCookies) }
    }

    @Test
    fun repeatedRequestsAndConfirmationsDispatchOnlyTheFirstPendingAction() {
        val gate = AuthLoginStorageGate()
        gate.request(requiresWarning = true, isPersistent = true) { browserCalls++ }
        gate.request(requiresWarning = true, isPersistent = true) { browserCalls += 100 }
        assertEquals(0, browserCalls)
        gate.confirm(isPersistent = true)
        gate.confirm(isPersistent = true)
        gate.request(requiresWarning = false, isPersistent = true) { browserCalls += 100 }
        assertEquals(1, browserCalls)
    }

    private fun assertBrowserWarning(style: UiStyle, dark: Boolean) {
        val stage = "${style.name.lowercase()}-${if (dark) "dark" else "light"}"
        render(requiresWarning = true, style = style, dark = dark)
        capture("$stage-before")
        action(browserAction).performNativeClick()
        awaitWarning()
        composeRule.runOnIdle { assertEquals(0, browserCalls) }
        capture("$stage-warning")
        action(string(CoreCommonR.string.action_cancel)).performNativeClick()
        composeRule.runOnIdle { assertEquals(0, browserCalls) }
        capture("$stage-cancelled")
        action(browserAction).performNativeClick()
        awaitWarning()
        action(string(CoreCommonR.string.auth_storage_compatibility_continue)).performNativeClick()
        composeRule.runOnIdle { assertEquals(1, browserCalls) }
        action(browserAction).assertIsNotEnabled()
        capture("$stage-confirmed")
    }

    private fun render(
        requiresWarning: Boolean,
        initialTab: Int = 0,
        style: UiStyle = UiStyle.Clean,
        dark: Boolean = false,
        importFailureMessage: String? = null
    ) {
        contentInstalled = true
        composeRule.setContent {
            if (visible.value) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = dark,
                    dynamicColor = false,
                    seedColorHex = "6750A4",
                    uiStyle = style,
                    animateColors = false
                ) {
                    SettingsCookieLoginSheet(
                        title = string(CoreCommonR.string.platform_bilibili),
                        initialTab = initialTab,
                        inlineMsg = inlineMessage.value,
                        onInlineMsgChange = { inlineMessage.value = it },
                        onDismiss = { visible.value = false },
                        browserTabLabel = string(CoreCommonR.string.login_qr),
                        browserButtonLabel = browserAction,
                        browserHintContent = { Text(string(CoreCommonR.string.settings_bili_login_browser_hint)) },
                        cookieLabel = cookieLabel,
                        onBrowserLogin = { browserCalls++ },
                        onSaveCookie = {
                            savedCookies += it
                            if (importFailureMessage != null) inlineMessage.value = importFailureMessage
                        },
                        requiresCompatibilityStorageWarning = requiresWarning,
                        isAuthStoragePersistent = persistent.value,
                        isAuthStorageReady = ready.value
                    )
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun enterCookie() {
        composeRule.onNodeWithText(cookieLabel).performTextInput(SYNTHETIC_COOKIE)
    }

    private fun awaitWarning() {
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(string(CoreCommonR.string.auth_storage_compatibility_message)).assertIsDisplayed()
    }

    private fun action(label: String) = composeRule.onNode(hasText(label) and hasClickAction())

    private fun string(resourceId: Int): String = composeRule.activity.getString(resourceId)

    private fun capture(stage: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf(String::isNotBlank)
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        if (prefix != null) {
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {
                val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
                val file = File(directory, "$prefix-$stage.png")
                file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                Log.i("AuthStorageWarning", "capture=${file.absolutePath}")
            } finally {
                bitmap.recycle()
            }
        }
        val holdMillis = arguments.getString("captureHoldMillis")?.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 0L
        if (holdMillis > 0L) Thread.sleep(holdMillis)
    }

    private companion object {
        const val SYNTHETIC_COOKIE = "SESSDATA=synthetic-ui-fixture"
    }
}
