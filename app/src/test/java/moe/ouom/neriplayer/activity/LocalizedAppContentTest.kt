package moe.ouom.neriplayer.activity

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import moe.ouom.neriplayer.util.platform.applyOnePlusHighDensityDisplayCorrection
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowBuild
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28, 32], application = Application::class, qualifiers = "en-rUS")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalizedAppContentTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val language = mutableStateOf(LanguageManager.Language.CHINESE)
    private val systemConfiguration = mutableStateOf(Configuration(Resources.getSystem().configuration))
    private val originalSystemConfiguration = Configuration(Resources.getSystem().configuration)

    @After
    fun restoreSystemConfiguration() {
        Resources.getSystem().configuration.setTo(originalSystemConfiguration)
    }

    @Test
    fun systemLanguageContinuesFollowingConfigurationAfterLeavingAnExplicitLanguage() {
        renderWithChineseBaseContext()
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        composeRule.runOnIdle { language.value = LanguageManager.Language.SYSTEM }
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        changeSystemLanguage("zh-CN")
        composeRule.onNodeWithText("设置").assertIsDisplayed()

        changeSystemLanguage("en-US")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    fun explicitLanguageRemainsSelectedWhenTheSystemLanguageChanges() {
        language.value = LanguageManager.Language.ENGLISH
        renderWithChineseBaseContext()
        composeRule.onNodeWithText("Settings").assertIsDisplayed()

        changeSystemLanguage("zh-CN")
        composeRule.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rUS-sw360dp-w360dp-h800dp-normal-port-notnight-560dpi")
    fun changingLanguageKeepsOnePlusResourceAndConfigurationDensity() {
        ShadowBuild.setManufacturer("OnePlus")
        ShadowBuild.setBrand("OnePlus")
        val application = ApplicationProvider.getApplicationContext<Application>()
        val originalLanguage = LanguageManager.getCurrentLanguage(application)
        val originalLocale = Locale.getDefault()
        try {
            LanguageManager.setLanguage(application, LanguageManager.Language.CHINESE)
            val corrected = applyOnePlusHighDensityDisplayCorrection(LanguageManager.applyLanguage(application))
            var resourceDensityDpi = 0
            var configurationDensityDpi = 0
            fun assertCorrectedDensity() = composeRule.runOnIdle {
                assertEquals(532, resourceDensityDpi)
                assertEquals(532, configurationDensityDpi)
            }
            composeRule.setContent {
                CompositionLocalProvider(
                    LocalContext provides corrected,
                    LocalConfiguration provides corrected.resources.configuration
                ) {
                    LocalizedAppContent(language.value) {
                        val resourceDensity = LocalResources.current.displayMetrics.densityDpi
                        val configurationDensity = LocalConfiguration.current.densityDpi
                        SideEffect {
                            resourceDensityDpi = resourceDensity
                            configurationDensityDpi = configurationDensity
                        }
                        Text(stringResource(CoreCommonR.string.nav_settings))
                    }
                }
            }
            composeRule.onNodeWithText("设置").assertIsDisplayed()
            assertCorrectedDensity()

            listOf(
                LanguageManager.Language.ENGLISH,
                LanguageManager.Language.SYSTEM,
                LanguageManager.Language.CHINESE
            ).forEach { selected ->
                composeRule.runOnIdle {
                    LanguageManager.setLanguage(application, selected)
                    language.value = selected
                }
                composeRule.onNodeWithText(
                    if (selected == LanguageManager.Language.CHINESE) "设置" else "Settings"
                ).assertIsDisplayed()
                assertCorrectedDensity()
            }
        } finally {
            LanguageManager.setLanguage(application, originalLanguage)
            Locale.setDefault(originalLocale)
        }
    }

    private fun renderWithChineseBaseContext() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val baseContext = LanguageManager.localizedContext(application, LanguageManager.Language.CHINESE)
        composeRule.setContent {
            CompositionLocalProvider(
                LocalContext provides baseContext,
                LocalConfiguration provides systemConfiguration.value
            ) {
                LocalizedAppContent(language.value) {
                    Text(stringResource(CoreCommonR.string.nav_settings))
                }
            }
        }
    }

    private fun changeSystemLanguage(languageTags: String) {
        composeRule.runOnIdle {
            Resources.getSystem().configuration.setLocales(LocaleList.forLanguageTags(languageTags))
            systemConfiguration.value = Configuration(Resources.getSystem().configuration)
        }
    }
}
