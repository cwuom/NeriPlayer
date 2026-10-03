package moe.ouom.neriplayer.activity

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.data.settings.SettingsRepository
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityLocalizedStartupTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    @Test
    fun configuredLanguageKeepsTheActivityAvailableDuringOnboarding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = SettingsRepository(context)
        val originalLanguage = LanguageManager.getCurrentLanguage(context)
        val originalDisclaimer = runBlocking { settings.disclaimerAcceptedFlow.filterNotNull().first() }
        val originalOnboarding = runBlocking { settings.startupOnboardingCompletedFlow.filterNotNull().first() }
        try {
            LanguageManager.setLanguage(context, LanguageManager.Language.CHINESE)
            runBlocking {
                settings.setDisclaimerAccepted(true)
                settings.setStartupOnboardingCompleted(false)
            }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var onboardingTitle = ""
                scenario.onActivity { onboardingTitle = it.getString(CoreCommonR.string.onboarding_title) }
                composeRule.waitForIdle()
                composeRule.onNodeWithText(onboardingTitle).assertIsDisplayed()
            }
        } finally {
            runBlocking {
                settings.setDisclaimerAccepted(originalDisclaimer)
                settings.setStartupOnboardingCompleted(originalOnboarding)
            }
            LanguageManager.setLanguage(context, originalLanguage)
        }
    }
}
