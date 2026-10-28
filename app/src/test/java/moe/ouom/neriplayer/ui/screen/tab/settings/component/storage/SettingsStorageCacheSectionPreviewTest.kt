package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class SettingsStorageCacheSectionPreviewTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `file name preview renders remote identity placeholders`() {
        assertEquals(
            "123456 - 456789 - 789012",
            buildDownloadFileNameTemplatePreview("%id% - %audioId% - %subAudioId%", context.resources)
        )
    }

    @Test
    fun `file name samples follow language changes without replacing the template`() {
        val locale = mutableStateOf(Locale.SIMPLIFIED_CHINESE)
        val template = "%title% - %artist% - %album% - %source% - %id%"
        val chinese = "晴天 - 周杰伦 - 叶惠美 - 网易云 - 123456"
        val english = "Sunny Day - Jay Chou - Ye Hui Mei - NetEase - 123456"
        composeRule.setContent {
            val configuration = Configuration(context.resources.configuration).apply {
                setLocale(locale.value)
            }
            val localizedContext = context.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalConfiguration provides configuration
            ) {
                Text(buildDownloadFileNameTemplatePreview(template, LocalResources.current))
            }
        }

        composeRule.onNodeWithText(chinese).assertExists()
        composeRule.runOnIdle { locale.value = Locale.ENGLISH }
        composeRule.onNodeWithText(english).assertExists()
        composeRule.onNodeWithText(chinese).assertDoesNotExist()
        composeRule.runOnIdle { locale.value = Locale.SIMPLIFIED_CHINESE }
        composeRule.onNodeWithText(chinese).assertExists()
        composeRule.onNodeWithText(english).assertDoesNotExist()
    }
}
