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
import moe.ouom.neriplayer.R as AppR
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsPage
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsHomePageGroups
import moe.ouom.neriplayer.ui.screen.tab.settings.page.settingsSearchScrollAnchor
import moe.ouom.neriplayer.ui.screen.tab.settings.page.buildSettingsSearchEntries
import moe.ouom.neriplayer.ui.screen.tab.settings.page.resolveSettingsSearchHighlightTarget
import moe.ouom.neriplayer.ui.screen.tab.settings.page.searchSettingsEntries
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
    private val internationalEnabled = mutableStateOf(false)
    private lateinit var navigation: SettingsNavigationState

    @Test
    fun languageAndContentSearchFindsEachAvailableControlOnItsCard() {
        val controls = mapOf(
            CoreCommonR.string.language_setting_title to "manual:language",
            CoreCommonR.string.settings_internationalization to "manual:internationalization",
            AppR.string.youtube_content_language to "manual:youtube_content_language",
            AppR.string.youtube_content_country to "manual:youtube_content_country"
        )
        val discoveryControls = mapOf(
            AppR.string.youtube_content_hide_explicit to "manual:youtube_content_hide_explicit",
            AppR.string.youtube_content_hide_videos to "manual:youtube_content_hide_videos"
        )
        assertEquals(1, SettingsHomePageGroups.flatten().count { it == SettingsPage.Language })
        assertTrue(SettingsPage.Content !in SettingsHomePageGroups.flatten())
        for (locale in listOf("en", "zh")) {
            val resources = resourcesFor(locale)
            for (international in listOf(false, true)) {
                val entries = buildSettingsSearchEntries(resources, international)
                assertTrue(entries.none { it.page == SettingsPage.Content })
                assertEquals(1, entries.count { it.id == "page:Language" })
                val expectedControls = if (international) controls + discoveryControls else controls
                if (!international) assertTrue(entries.none { it.targetId in discoveryControls.values })
                expectedControls.forEach { (title, target) ->
                    val label = resources.getString(title)
                    val control = entries.single { it.title == label }
                    assertEquals(SettingsPage.Language, control.page)
                    assertEquals(target, control.id)
                    assertEquals(target, resolveSettingsSearchHighlightTarget(control, dynamicColor = false))
                    val card = if (target in discoveryControls.values) 2 else 1
                    assertEquals(card, settingsSearchScrollAnchor(control.page, control.targetId).itemIndex)
                    assertTrue(searchSettingsEntries(entries, label, limit = 8).contains(control))
                }
            }
        }
    }

    @Test
    fun internationalRecommendationKindsFindTheirLocalizedSwitchesOnlyInInternationalMode() {
        val titles = mapOf(
            "track" to CoreCommonR.string.artist_tab_songs,
            "playlist" to CoreCommonR.string.library_netease_tab_playlists,
            "album" to CoreCommonR.string.library_netease_tab_albums,
            "artist" to CoreCommonR.string.explore_search_type_artist
        )
        for (locale in listOf("en", "zh")) {
            val resources = resourcesFor(locale)
            val international = buildSettingsSearchEntries(resources, internationalEnabled = true)
            val domestic = buildSettingsSearchEntries(resources, internationalEnabled = false)
            titles.forEach { (kind, title) ->
                val target = "manual:international_home_feed_$kind"
                val label = resources.getString(title)
                val entry = international.single { it.targetId == target }
                assertEquals(label, entry.title)
                assertEquals(resources.getString(CoreCommonR.string.international_home_feed), entry.description)
                assertEquals(SettingsPage.Personalization, entry.page)
                assertEquals(2, settingsSearchScrollAnchor(entry.page, target).itemIndex)
                assertTrue(searchSettingsEntries(international, label).contains(entry))
                assertTrue(domestic.none { it.targetId == target })
            }
        }
    }

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

    @Test
    fun internationalModeRemovesTheBilibiliHomeSwitchWithoutResettingSearchState() {
        render(updateHostResources = true)
        composeRule.runOnIdle { navigation.searchQueryState.value = "bilibili" }
        val queryState = navigation.searchQueryState
        val pageState = navigation.activePageState
        val listState = navigation.detailListStates.getValue(SettingsPage.Personalization)
        val targetId = "setting:home_card_bilibili"
        composeRule.runOnIdle {
            assertTrue(navigation.searchResults.any { it.targetId == targetId })
        }

        listOf(true, false, true, false).forEach { enabled ->
            composeRule.runOnIdle { internationalEnabled.value = enabled }
            composeRule.runOnIdle {
                assertEquals(!enabled, navigation.searchResults.any { it.targetId == targetId })
                assertTrue(navigation.searchResults.any { it.targetId != targetId })
                assertEquals("bilibili", navigation.searchQueryState.value)
                assertTrue(queryState === navigation.searchQueryState)
                assertTrue(pageState === navigation.activePageState)
                assertTrue(listState === navigation.detailListStates.getValue(SettingsPage.Personalization))
            }
        }
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
                    splitLayout = false,
                    internationalEnabled = internationalEnabled.value
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
