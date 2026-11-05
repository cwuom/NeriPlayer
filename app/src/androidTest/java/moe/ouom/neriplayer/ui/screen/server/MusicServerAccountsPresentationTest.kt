package moe.ouom.neriplayer.ui.screen.server

import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.auth.SettingsMusicServerCard
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServerAccountsPresentationTest {
    @get:Rule val composeRule = createComposeRule()
    private lateinit var hostView: View

    @Before
    fun requireComposeHost() = assumeComposeHostAvailable()

    @Test fun cleanLightAccountForm() = verifyNewAccount(UiStyle.Clean, dark = false)
    @Test fun cleanDarkAccountForm() = verifyNewAccount(UiStyle.Clean, dark = true)
    @Test fun materialLightAccountForm() = verifyNewAccount(UiStyle.Material, dark = false)
    @Test fun materialDarkAccountForm() = verifyNewAccount(UiStyle.Material, dark = true)

    @Test fun cleanEditKeepsIdentityAndDoesNotPopulatePassword() = verifyEdit(UiStyle.Clean)
    @Test fun materialEditKeepsIdentityAndDoesNotPopulatePassword() = verifyEdit(UiStyle.Material)

    @Test fun cleanPendingConnectionDisablesCancellation() = verifyPending(UiStyle.Clean)
    @Test fun materialPendingConnectionDisablesCancellation() = verifyPending(UiStyle.Material)

    @Test fun cleanRemovalHonorsBusyState() = verifyRemoval(UiStyle.Clean)
    @Test fun materialRemovalHonorsBusyState() = verifyRemoval(UiStyle.Material)

    @Test fun cleanAccountEntryOpensManagement() = verifyEntry(UiStyle.Clean)
    @Test fun materialAccountEntryOpensManagement() = verifyEntry(UiStyle.Material)

    @Test fun cleanLargeFontSaveFailureIsVisibleAndRetryable() = verifySaveFailure(UiStyle.Clean)
    @Test fun materialLargeFontSaveFailureIsVisibleAndRetryable() = verifySaveFailure(UiStyle.Material)

    private fun verifySaveFailure(style: UiStyle) {
        var attempts = 0
        var dismisses = 0
        val unsafeDetail = "fixture-detail-must-not-be-displayed"
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                AccountFixture(style) {
                    MusicServerAccountEditorDialog(profile = null, onDismiss = { dismisses++ },
                        onSave = { _, _, _, _ ->
                            attempts++
                            if (attempts == 1) throw IllegalArgumentException(unsafeDetail)
                        })
                }
            }
        }
        composeRule.onNodeWithTag("serverAccountAddress").performScrollTo().performTextInput("http://10.0.2.2:45331/music")
        composeRule.onNodeWithTag("serverAccountUsername").performScrollTo().performTextInput("fixture-user")
        composeRule.onNodeWithTag("serverAccountPassword").performScrollTo().performClick().performTextInput("fixture-only")
        composeRule.onNodeWithTag("serverAccountLabel").performScrollTo()
        composeRule.onNodeWithTag("serverAccountSave").assertIsEnabled().performClick()

        val safeMessage = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(CoreCommonR.string.server_error_input)
        composeRule.onNodeWithText(safeMessage).assertIsDisplayed()
        composeRule.onNodeWithText(unsafeDetail).assertDoesNotExist()
        composeRule.onNodeWithTag("serverAccountSave").assertIsEnabled()
        composeRule.onNodeWithTag("serverAccountCancel").assertIsEnabled()
        capture("server-account-error-${style.storageValue}-large-font")
        composeRule.onNodeWithTag("serverAccountSave").performClick()
        composeRule.runOnIdle {
            assertEquals(2, attempts)
            assertEquals(1, dismisses)
        }
        composeRule.onNodeWithText(safeMessage).assertDoesNotExist()
    }

    private fun verifyNewAccount(style: UiStyle, dark: Boolean) {
        var saves = 0
        var dismisses = 0
        composeRule.setContent {
            AccountFixture(style, dark) {
                MusicServerAccountEditorDialog(profile = null, onDismiss = { dismisses++ },
                    onSave = { _, _, _, _ -> saves++ })
            }
        }
        composeRule.onNodeWithTag("serverAccountSave").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountPassword")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        composeRule.onNodeWithTag("serverAccountLabel").performTextInput("Local fixture")
        composeRule.onNodeWithTag("serverAccountAddress").performTextInput("http://10.0.2.2:45331/music")
        composeRule.onNodeWithTag("serverAccountUsername").performTextInput("fixture-user")
        composeRule.onNodeWithTag("serverAccountSave").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountPassword").performTextInput("fixture-only")
        composeRule.onNodeWithTag("serverAccountSave").assertIsEnabled()
        capture("server-account-${style.storageValue}-${if (dark) "dark" else "light"}")
        composeRule.onNodeWithTag("serverAccountCancel").performClick()
        composeRule.runOnIdle {
            assertEquals(0, saves)
            assertEquals(1, dismisses)
        }
    }

    private fun verifyEdit(style: UiStyle) {
        var savedPassword: String? = null
        composeRule.setContent {
            AccountFixture(style) {
                MusicServerAccountEditorDialog(profile, onDismiss = {},
                    onSave = { _, address, username, password ->
                        assertEquals(profile.baseUrl, address)
                        assertEquals(profile.username, username)
                        savedPassword = password
                    })
            }
        }
        composeRule.onNodeWithTag("serverAccountAddress").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountUsername").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountPassword").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        composeRule.onNodeWithTag("serverAccountSave").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals("", savedPassword) }
    }

    private fun verifyPending(style: UiStyle) {
        val saved = CompletableDeferred<Unit>()
        composeRule.setContent {
            AccountFixture(style) {
                MusicServerAccountEditorDialog(profile, onDismiss = {},
                    onSave = { _, _, _, _ -> saved.await() })
            }
        }
        composeRule.onNodeWithTag("serverAccountSave").performClick()
        composeRule.onNodeWithTag("serverAccountSave").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountCancel").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountPassword").assertIsNotEnabled()
        composeRule.runOnIdle { saved.complete(Unit) }
    }

    private fun verifyRemoval(style: UiStyle) {
        composeRule.setContent {
            AccountFixture(style) {
                MusicServerAccountRemovalDialog(profile, busy = true, onDismiss = {}, onConfirm = {})
            }
        }
        composeRule.onNodeWithTag("serverAccountRemoveConfirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("serverAccountRemoveCancel").assertIsNotEnabled()
        capture("server-account-remove-${style.storageValue}")
    }

    private fun verifyEntry(style: UiStyle) {
        var opened = 0
        composeRule.setContent {
            AccountFixture(style) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(16.dp)) {
                        SettingsMusicServerCard { opened++ }
                    }
                }
            }
        }
        composeRule.onNodeWithTag("settingsMusicServerCard").assertIsDisplayed().performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }
    }

    private fun capture(name: String) {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("captureMusicServerAccounts") != "true" &&
            arguments.getString("captureMusicServerUi") != "true") return
        composeRule.captureMusicServerUi("$name.png", hostView)
    }

    @Composable
    private fun AccountFixture(style: UiStyle, dark: Boolean = false, content: @Composable () -> Unit) {
        hostView = LocalView.current
        NeriTheme(followSystemDark = false, forceDark = dark, dynamicColor = false,
            seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
            Surface(Modifier.fillMaxSize()) { content() }
        }
    }

    private val profile = SubsonicProfile("fixture-profile", "Local fixture",
        "http://10.0.2.2:45331/music/", "fixture-user")
}
