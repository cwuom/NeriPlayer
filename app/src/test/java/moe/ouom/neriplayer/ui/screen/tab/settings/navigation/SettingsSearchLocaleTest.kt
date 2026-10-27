package moe.ouom.neriplayer.ui.screen.tab.settings.navigation

import android.app.Application
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Locale

@RunWith(AndroidJUnit4::class)
@Config(sdk = [32], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSearchLocaleTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val language = mutableStateOf("en")
    private lateinit var navigation: SettingsNavigationState

    @Test
    fun searchTitlesAndTokensFollowLanguageChangesWhenTheContextIsReused() {
        render(updateHostResources = true)
        composeRule.runOnIdle { navigation.searchQueryState.value = "blueto" }
        val queryState = navigation.searchQueryState
        val pageState = navigation.activePageState
        val listState = navigation.detailListStates.getValue(SettingsPage.ExternalLyrics)

        listOf("en", "zh", "en", "zh").forEach { locale ->
            composeRule.runOnIdle { language.value = locale }
            assertSearchLanguage(locale)
            composeRule.runOnIdle {
                assertEquals("blueto", navigation.searchQueryState.value)
                assertTrue(queryState === navigation.searchQueryState)
                assertTrue(pageState === navigation.activePageState)
                assertTrue(listState === navigation.detailListStates.getValue(SettingsPage.ExternalLyrics))
            }
        }
        composeRule.runOnIdle { navigation.searchQueryState.value = "蓝牙" }
        composeRule.runOnIdle {
            assertTrue(navigation.searchResults.any { it.page == SettingsPage.ExternalLyrics })
        }
    }

    @Test
    fun searchUsesTheSameResourcesAsTheVisibleCategoryWhenTheHostLocaleDiffers() {
        render(updateHostResources = false)
        composeRule.runOnIdle { navigation.searchQueryState.value = "blueto" }
        composeRule.runOnIdle { language.value = "zh" }
        assertSearchLanguage("zh")
    }

    private fun render(updateHostResources: Boolean) {
        var hostResources = resourcesFor("en")
        val hostContext = object : ContextWrapper(application) {
            override fun getResources(): Resources = hostResources
        }
        composeRule.setContent {
            val resources = resourcesFor(language.value)
            if (updateHostResources) hostResources = resources
            CompositionLocalProvider(
                LocalContext provides hostContext,
                LocalConfiguration provides Configuration(resources.configuration),
                LocalResources provides resources
            ) {
                navigation = rememberSettingsNavigationState(
                    listState = rememberLazyListState(),
                    dynamicColor = false,
                    mobileDataFollowDefaultAudioQuality = false,
                    backgroundImageUri = null,
                    splitLayout = false
                )
                Column {
                    Text(stringResource(CoreCommonR.string.settings_title))
                    SettingsSearchResultsCard(navigation.searchResults, onResultClick = {})
                }
            }
        }
    }

    private fun assertSearchLanguage(locale: String) {
        val expected = resourcesFor(locale).getString(SettingsPage.ExternalLyrics.titleRes)
        composeRule.runOnIdle {
            assertEquals(expected, navigation.searchResults.first { it.id == "page:ExternalLyrics" }.title)
        }
        assertTrue(composeRule.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty())
        val oldLocale = if (locale == "en") "zh" else "en"
        val staleTitle = resourcesFor(oldLocale).getString(SettingsPage.ExternalLyrics.titleRes)
        assertTrue(composeRule.onAllNodesWithText(staleTitle).fetchSemanticsNodes().isEmpty())
    }

    private fun resourcesFor(locale: String): Resources = application.createConfigurationContext(
        Configuration().apply { setLocale(Locale.forLanguageTag(locale)) }
    ).resources
}
