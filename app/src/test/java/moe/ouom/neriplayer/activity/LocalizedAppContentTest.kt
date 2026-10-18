package moe.ouom.neriplayer.activity

import android.app.Application
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.common.locale.LanguageManager
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

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
