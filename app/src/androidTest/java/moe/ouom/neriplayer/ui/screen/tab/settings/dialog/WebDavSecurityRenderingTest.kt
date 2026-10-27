package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncUiState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
class WebDavSecurityRenderingTest {
    @get:Rule val composeRule = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun requireComposeHost() {
        assumeComposeHostAvailable()
    }

    @Test
    fun materialLightCleartextConfirmation() = verifyConfirmation(UiStyle.Material, dark = false)

    @Test
    fun materialDarkCleartextConfirmation() = verifyConfirmation(UiStyle.Material, dark = true)

    @Test
    fun cleanLightCleartextConfirmation() = verifyConfirmation(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkCleartextConfirmation() = verifyConfirmation(UiStyle.Clean, dark = true)

    private fun verifyConfirmation(style: UiStyle, dark: Boolean) {
        var submissions = 0
        val draft = WebDavConfigDraft(
            mutableStateOf("http://dav.example.invalid"),
            mutableStateOf("example-user"),
            mutableStateOf("example-password"),
            mutableStateOf("NeriPlayer")
        )
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = style,
                animateColors = false
            ) {
                WebDavConfigurationDialog(
                    webDavState = WebDavSyncUiState(),
                    draft = draft,
                    onDismiss = {},
                    onClearMessages = {},
                    onValidateAndSave = { submissions++ }
                )
            }
        }
        val prefix = "webdav-${style.storageValue}-${if (dark) "dark" else "light"}"
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertIsDisplayed()
        capture("$prefix-form")
        composeRule.onNodeWithText(string(CoreCommonR.string.webdav_validate_and_save)).performClick()

        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertIsDisplayed()
        composeRule.runOnIdle { assertEquals(0, submissions) }
        capture("$prefix-confirmation")
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_cancel)).performClick()
        composeRule.runOnIdle { assertEquals(0, submissions) }
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertDoesNotExist()
        capture("$prefix-cancelled")

        composeRule.onNodeWithText(string(CoreCommonR.string.webdav_validate_and_save)).performClick()
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm)).performClick()
        composeRule.runOnIdle { assertEquals(1, submissions) }
    }

    private fun capture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("captureWebDavSecurity") != "true") return
        composeRule.waitForIdle()
        SystemClock.sleep(1_000)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            context.cacheDir.resolve("$name.png").outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun string(id: Int): String = context.getString(id)
}
