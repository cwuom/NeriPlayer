package moe.ouom.neriplayer.ui.screen.tab.settings.backup

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.pressBack
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isRoot
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
class SettingsConfigProtectionDialogsTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<TestComposeActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val originalMotionStyle = NeriMotionStyle.clean
    private val dialog = mutableStateOf(Dialog.Export)
    private val passwordInvalid = mutableStateOf(false)
    private val exports = mutableListOf<Pair<Boolean, String?>>()
    private val imports = mutableListOf<String>()
    private var dismissCalls = 0
    private var retainAfterConfirm = false
    private var contentInstalled = false

    @Before
    fun verifyHost() {
        assumeComposeHostAvailable()
    }

    @After
    fun clearDialog() {
        try {
            if (contentInstalled) {
                composeRule.runOnIdle { dialog.value = Dialog.None }
                composeRule.waitForIdle()
            }
        } finally {
            NeriMotionStyle.clean = originalMotionStyle
        }
    }

    @Test
    fun cleanLightValidatesExportAndCancelsSafely() = assertExport(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkValidatesExportAndCancelsSafely() = assertExport(UiStyle.Clean, dark = true)

    @Test
    fun materialLightValidatesExportAndCancelsSafely() = assertExport(UiStyle.Material, dark = false)

    @Test
    fun materialDarkValidatesExportAndCancelsSafely() = assertExport(UiStyle.Material, dark = true)

    @Test
    fun cleanLightShowsImportFailureAndClearsSubmittedPassword() = assertImport(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkShowsImportFailureAndClearsSubmittedPassword() = assertImport(UiStyle.Clean, dark = true)

    @Test
    fun materialLightShowsImportFailureAndClearsSubmittedPassword() = assertImport(UiStyle.Material, dark = false)

    @Test
    fun materialDarkShowsImportFailureAndClearsSubmittedPassword() = assertImport(UiStyle.Material, dark = true)

    private fun assertExport(style: UiStyle, dark: Boolean) {
        render(style, dark, Dialog.Export)
        action(CoreCommonR.string.settings_config_include_login_state).assertIsOff()
        action(CoreCommonR.string.settings_config_encrypt_file).assertIsOff()
        capture(style, dark, "export-default")
        action(CoreCommonR.string.settings_backup_action_export).performNativeClick()
        composeRule.runOnIdle { assertEquals(listOf(false to null), exports) }

        open(Dialog.Export)
        action(CoreCommonR.string.settings_config_include_login_state).performClick().assertIsOn()
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_plaintext_login_warning))
            .performScrollTo().assertIsDisplayed()
        capture(style, dark, "export-plaintext-warning")
        action(CoreCommonR.string.action_cancel).performNativeClick()
        composeRule.runOnIdle {
            assertEquals(1, exports.size)
            assertEquals(1, dismissCalls)
        }

        open(Dialog.Export)
        action(CoreCommonR.string.settings_config_include_login_state).assertIsOff().performClick()
        action(CoreCommonR.string.settings_config_encrypt_file).performScrollTo().performClick()
        action(CoreCommonR.string.settings_backup_action_export).assertIsNotEnabled()
        replacePassword(CoreCommonR.string.settings_config_password, "short")
        replacePassword(CoreCommonR.string.settings_config_confirm_password, "short")
        action(CoreCommonR.string.settings_backup_action_export).assertIsNotEnabled()
        replacePassword(CoreCommonR.string.settings_config_password, "        ")
        replacePassword(CoreCommonR.string.settings_config_confirm_password, "        ")
        action(CoreCommonR.string.settings_backup_action_export).assertIsNotEnabled()
        replacePassword(CoreCommonR.string.settings_config_password, SYNTHETIC_PASSWORD)
        replacePassword(CoreCommonR.string.settings_config_confirm_password, "Different-demo-password")
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_password_mismatch))
            .performScrollTo().assertIsDisplayed()
        action(CoreCommonR.string.settings_backup_action_export).assertIsNotEnabled()
        replacePassword(CoreCommonR.string.settings_config_confirm_password, SYNTHETIC_PASSWORD)
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        action(CoreCommonR.string.settings_backup_action_export).assertIsEnabled()
        capture(style, dark, "export-encrypted")
        composeRule.runOnIdle { retainAfterConfirm = true }
        action(CoreCommonR.string.settings_backup_action_export).performNativeClick()
        composeRule.runOnIdle {
            assertEquals(listOf(false to null, true to SYNTHETIC_PASSWORD), exports)
        }
        assertPasswordCleared(CoreCommonR.string.settings_config_password)
        assertPasswordCleared(CoreCommonR.string.settings_config_confirm_password)
        action(CoreCommonR.string.settings_backup_action_export).assertIsNotEnabled()
        action(CoreCommonR.string.action_cancel).performNativeClick()

        open(Dialog.Export)
        action(CoreCommonR.string.settings_config_encrypt_file).assertIsOff().performClick()
        replacePassword(CoreCommonR.string.settings_config_password, SYNTHETIC_PASSWORD)
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard(), pressBack())
        composeRule.runOnIdle {
            assertEquals(2, exports.size)
            assertEquals(3, dismissCalls)
        }
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_export_options_title))
            .assertDoesNotExist()
    }

    private fun assertImport(style: UiStyle, dark: Boolean) {
        render(style, dark, Dialog.Import)
        action(CoreCommonR.string.settings_backup_action_import).assertIsNotEnabled()
        action(CoreCommonR.string.action_cancel).performNativeClick()
        composeRule.runOnIdle { assertTrue(imports.isEmpty()) }

        open(Dialog.Import)
        onView(isRoot()).inRoot(isDialog()).perform(pressBack())
        composeRule.runOnIdle {
            assertTrue(imports.isEmpty())
            assertEquals(2, dismissCalls)
        }

        open(Dialog.Import)
        replacePassword(CoreCommonR.string.settings_config_password, SYNTHETIC_PASSWORD)
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        composeRule.runOnIdle { retainAfterConfirm = true }
        action(CoreCommonR.string.settings_backup_action_import).performNativeClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SYNTHETIC_PASSWORD), imports)
            passwordInvalid.value = true
        }
        assertPasswordCleared(CoreCommonR.string.settings_config_password)
        action(CoreCommonR.string.settings_backup_action_import).assertIsNotEnabled()
        composeRule.onNodeWithText(string(CoreCommonR.string.settings_config_password_invalid))
            .assertIsDisplayed()
        capture(style, dark, "import-password-error")
        replacePassword(CoreCommonR.string.settings_config_password, "Retry-demo-password")
        onView(isRoot()).inRoot(isDialog()).perform(closeSoftKeyboard())
        action(CoreCommonR.string.settings_backup_action_import).assertIsEnabled().performNativeClick()
        composeRule.runOnIdle {
            assertEquals(listOf(SYNTHETIC_PASSWORD, "Retry-demo-password"), imports)
        }
        assertPasswordCleared(CoreCommonR.string.settings_config_password)
    }

    private fun replacePassword(id: Int, value: String) {
        composeRule.onNodeWithText(string(id)).performScrollTo().performTextReplacement(value)
    }

    private fun assertPasswordCleared(id: Int) {
        val text = composeRule.onNodeWithText(string(id)).fetchSemanticsNode()
            .config[SemanticsProperties.EditableText].text
        assertEquals("", text)
    }

    private fun open(value: Dialog) {
        composeRule.runOnIdle { dialog.value = value }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun render(style: UiStyle, dark: Boolean, initialDialog: Dialog) {
        dialog.value = initialDialog
        contentInstalled = true
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = style,
                animateColors = false
            ) {
                val dismiss = {
                    dismissCalls++
                    dialog.value = Dialog.None
                }
                when (dialog.value) {
                    Dialog.Export -> SettingsConfigExportOptionsDialog(
                        onConfirm = { includeLoginState, password ->
                            exports += includeLoginState to password
                            if (!retainAfterConfirm) dialog.value = Dialog.None
                        },
                        onDismiss = dismiss
                    )
                    Dialog.Import -> SettingsConfigImportPasswordDialog(
                        isPasswordInvalid = passwordInvalid.value,
                        onConfirm = { password ->
                            imports += password
                            if (!retainAfterConfirm) dialog.value = Dialog.None
                        },
                        onDismiss = dismiss
                    )
                    Dialog.None -> Unit
                }
            }
        }
        composeRule.mainClock.advanceTimeBy(500L)
        composeRule.waitForIdle()
    }

    private fun action(id: Int) = composeRule.onNode(hasText(string(id)) and hasClickAction())

    private fun string(id: Int): String = composeRule.activity.getString(id)

    private fun capture(style: UiStyle, dark: Boolean, stage: String) {
        val arguments = InstrumentationRegistry.getArguments()
        val prefix = arguments.getString("capturePrefix")
            ?.replace(Regex("[^a-zA-Z0-9_-]"), "-")?.take(80)?.takeIf(String::isNotBlank) ?: return
        composeRule.waitForIdle()
        instrumentation.waitForIdleSync()
        val holdMillis = arguments.getString("captureHoldMillis")?.toLongOrNull()?.coerceIn(0L, 10_000L) ?: 0L
        if (holdMillis > 0L) {
            Thread.sleep(holdMillis)
            composeRule.waitForIdle()
            instrumentation.waitForIdleSync()
        }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val directory = checkNotNull(instrumentation.targetContext.getExternalFilesDir(null))
            val mode = if (dark) "dark" else "light"
            val file = File(directory, "$prefix-config-protection-${style.storageValue}-$mode-$stage.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            Log.i("ConfigProtection", "capture=${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private enum class Dialog { Export, Import, None }

    private companion object {
        const val SYNTHETIC_PASSWORD = "Synthetic-demo-password"
    }
}
