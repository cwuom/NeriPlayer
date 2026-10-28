package moe.ouom.neriplayer.ui.screen.tab.settings.dialog

import android.app.Application
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.ui.viewmodel.WebDavSyncUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class WebDavConfigurationSecurityTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val submissions = mutableListOf<String>()
    private val draft = WebDavConfigDraft(
        mutableStateOf("http://dav.example.invalid"),
        mutableStateOf("user"),
        mutableStateOf("password"),
        mutableStateOf("NeriPlayer")
    )

    @Test
    fun `material light requires confirmation before submitting cleartext credentials`() =
        verifyHttpConfirmation(UiStyle.Material, dark = false)

    @Test
    fun `material dark requires confirmation before submitting cleartext credentials`() =
        verifyHttpConfirmation(UiStyle.Material, dark = true)

    @Test
    fun `clean light requires confirmation before submitting cleartext credentials`() =
        verifyHttpConfirmation(UiStyle.Clean, dark = false)

    @Test
    fun `clean dark requires confirmation before submitting cleartext credentials`() =
        verifyHttpConfirmation(UiStyle.Clean, dark = true)

    @Test
    fun `https submits directly without a cleartext warning`() {
        draft.serverUrl.value = "https://dav.example.invalid"
        showDialog(UiStyle.Material)

        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertDoesNotExist()
        submit()

        assertEquals(listOf("https://dav.example.invalid"), submissions)
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertDoesNotExist()
    }

    @Test
    fun `editing the scheme updates the warning immediately`() {
        draft.serverUrl.value = "https://dav.example.invalid"
        showDialog(UiStyle.Clean)
        val field = composeRule.onNode(hasSetTextAction() and hasText("https://dav.example.invalid"))

        field.performTextReplacement("  HTTP://dav.example.invalid  ")
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertIsDisplayed()

        composeRule.onNode(hasSetTextAction() and hasText("  HTTP://dav.example.invalid  "))
            .performTextReplacement("https://dav.example.invalid")
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertDoesNotExist()
        assertEquals(emptyList<String>(), submissions)
    }

    @Test
    fun `changing the configuration discards an outstanding confirmation`() {
        showDialog(UiStyle.Clean)
        submit()
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertIsDisplayed()

        composeRule.runOnIdle { draft.serverUrl.value = "http://another.example.invalid" }

        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertDoesNotExist()
        assertEquals(emptyList<String>(), submissions)
        submit()
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm)).performClick()
        assertEquals(listOf("http://another.example.invalid"), submissions)
    }

    @Test
    fun `saved cleartext warning disappears when the configured target becomes https`() {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "6750A4",
                uiStyle = UiStyle.Clean,
                animateColors = false
            ) {
                WebDavCleartextWarning(serverUrl = draft.serverUrl.value)
            }
        }
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertIsDisplayed()

        composeRule.runOnIdle { draft.serverUrl.value = "https://dav.example.invalid" }

        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertDoesNotExist()
    }

    private fun verifyHttpConfirmation(style: UiStyle, dark: Boolean) {
        showDialog(style, dark)
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_warning)).assertIsDisplayed()
        submit()

        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertIsDisplayed()
        assertEquals(emptyList<String>(), submissions)
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_cancel)).performClick()
        assertEquals(emptyList<String>(), submissions)
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertDoesNotExist()

        submit()
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm)).performClick()

        assertEquals(listOf("http://dav.example.invalid"), submissions)
        composeRule.onNodeWithText(string(AppR.string.webdav_cleartext_confirm_title)).assertDoesNotExist()
    }

    private fun showDialog(style: UiStyle, dark: Boolean = false) {
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
                    onValidateAndSave = { submissions += draft.serverUrl.value }
                )
            }
        }
    }

    private fun submit() {
        composeRule.onNodeWithText(string(CoreCommonR.string.webdav_validate_and_save)).performClick()
    }

    private fun string(id: Int): String = context.getString(id)
}
