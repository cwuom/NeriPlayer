package moe.ouom.neriplayer.activity

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.R
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.core.startup.debug.DebugBuildWarningRepository
import moe.ouom.neriplayer.core.startup.debug.debugBuildWarningDataStore
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.settings.dataStore
import moe.ouom.neriplayer.ui.theme.NeriMotionStyle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityPreviewThemePromptTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val settings = SettingsRepository(context)
    private lateinit var originalSettings: Preferences
    private lateinit var originalDebugWarning: Preferences
    private var originalStyle = UiStyle.Material
    private var originalDisclaimer = false
    private var originalOnboarding = false
    private var originalCleanMotion = false

    @Before
    fun prepareUnansweredMaterialInstallation() = runBlocking {
        originalCleanMotion = NeriMotionStyle.clean
        originalSettings = context.dataStore.data.first()
        originalDebugWarning = context.debugBuildWarningDataStore.data.first()
        originalStyle = settings.uiStyleFlow.first()
        originalDisclaimer = settings.disclaimerAcceptedFlow.first() == true
        originalOnboarding = settings.startupOnboardingCompletedFlow.first() == true
        settings.setUiStyle(UiStyle.Material)
        settings.setDisclaimerAccepted(true)
        settings.setStartupOnboardingCompleted(true)
        context.dataStore.edit { it.remove(booleanPreferencesKey("preview_theme_prompt_answered")) }
        DebugBuildWarningRepository(context).acknowledge()
    }

    @After
    fun restoreInstallationSettings() = runBlocking {
        try {
            settings.setUiStyle(originalStyle)
            settings.setDisclaimerAccepted(originalDisclaimer)
            settings.setStartupOnboardingCompleted(originalOnboarding)
            context.dataStore.updateData { originalSettings }
            context.debugBuildWarningDataStore.updateData { originalDebugWarning }
        } finally {
            NeriMotionStyle.clean = originalCleanMotion
        }
        Unit
    }

    private fun awaitPrompt() {
        val title = context.getString(R.string.preview_theme_prompt_title)
        composeRule.waitUntil(timeoutMillis = 20_000L) {
            composeRule.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(title).assertIsDisplayed()
        if (InstrumentationRegistry.getArguments().getString("capturePreviewThemePrompt") == "true") {
            composeRule.mainClock.advanceTimeBy(500L)
            composeRule.waitForIdle()
            val bitmap = checkNotNull(
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            )
            context.cacheDir.resolve("preview-theme-dialog.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun upgradedInstallationCanApplyPreviewImmediatelyWithoutRecreatingActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitPrompt()
            composeRule.runOnIdle { assertFalse(NeriMotionStyle.clean) }
            composeRule.onNodeWithText(context.getString(R.string.preview_theme_prompt_enable))
                .performClick()

            composeRule.waitUntil(timeoutMillis = 10_000L) { NeriMotionStyle.clean }
            assertEquals(UiStyle.Clean, runBlocking { settings.uiStyleFlow.first() })
            composeRule.onNodeWithText(context.getString(R.string.preview_theme_prompt_title))
                .assertDoesNotExist()

            scenario.recreate()
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                composeRule.onAllNodesWithText(context.getString(CoreCommonR.string.nav_home))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.preview_theme_prompt_title))
                .assertDoesNotExist()
        }
    }

    @Test
    fun onboardingCompletionMakesPreviewInvitationAvailable() {
        runBlocking { settings.setStartupOnboardingCompleted(false) }
        ActivityScenario.launch(MainActivity::class.java).use {
            val onboardingTitle = context.getString(CoreCommonR.string.onboarding_title)
            composeRule.waitUntil(timeoutMillis = 20_000L) {
                composeRule.onAllNodesWithText(onboardingTitle).fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText(context.getString(R.string.preview_theme_prompt_title))
                .assertDoesNotExist()

            // 使用引导完成回调的同一持久化入口, 验证 Activity 实际启动门禁
            runBlocking { settings.setStartupOnboardingCompleted(true) }
            awaitPrompt()
            composeRule.onNodeWithText(context.getString(R.string.preview_theme_prompt_decline))
                .performClick()
            composeRule.waitUntil(timeoutMillis = 10_000L) {
                composeRule.onAllNodesWithText(context.getString(R.string.preview_theme_prompt_title))
                    .fetchSemanticsNodes().isEmpty()
            }
            assertEquals(UiStyle.Material, runBlocking { settings.uiStyleFlow.first() })
        }
    }
}
