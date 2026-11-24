package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeLayout
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeFeedKindIds
import moe.ouom.neriplayer.data.model.settings.home.InternationalHomeSectionIds
import moe.ouom.neriplayer.ui.settings.route.AppHomeCardsSettingsState
import moe.ouom.neriplayer.ui.settings.route.AppHomeSettingsActions
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w480dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeSectionControlsTest {
    @get:Rule val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val repository = mock(SettingsRepository::class.java)
    private val initial = AppHomeCardsSettingsState(
        showHomeContinueCard = true,
        showHomeTrendingCard = true,
        showHomeRadarCard = true,
        showHomeRecommendedCard = true,
        showHomeBilibiliCard = true
    )

    @Test
    fun majorSwitchesDispatchToTheirOwnVisibilitySetting() {
        render(initial.copy(showHomeRadarCard = false, showHomeRecommendedCard = false))
        switch(CoreCommonR.string.player_continue).assertIsOn().performClick()
        switch(CoreCommonR.string.settings_home_card_netease_trending).assertIsOn().performClick()
        switch(CoreCommonR.string.settings_home_card_netease_radar).assertIsOff().performClick()
        switch(CoreCommonR.string.settings_home_card_netease_recommended).assertIsOff().performClick()
        switch(CoreCommonR.string.settings_home_card_bilibili).assertIsOn().performClick()
        composeRule.waitForIdle()

        runBlocking {
            verify(repository).setHomeCardContinue(false)
            verify(repository).setHomeCardTrending(false)
            verify(repository).setHomeCardRadar(true)
            verify(repository).setHomeCardRecommended(true)
            verify(repository).setHomeCardBilibili(false)
        }
    }

    @Test
    fun expandedGroupsKeepTheirOwnHiddenSectionsAndReorderWithinTheGroup() {
        render(initial.copy(trendingHidden = setOf(HomeTrendingSubSectionIds.TOP_HOT)))

        expand(CoreCommonR.string.settings_home_card_netease_trending)
        switch(CoreCommonR.string.home_netease_hot_rank).assertIsOff().performClick()
        move(CoreCommonR.string.recommend_trending, CoreCommonR.string.home_move_down)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_home_card_netease_trending)).performClick()

        expand(CoreCommonR.string.settings_home_card_netease_radar)
        switch(CoreCommonR.string.home_netease_radar_playlists).assertIsOn().performClick()
        move(CoreCommonR.string.home_netease_radar_playlists, CoreCommonR.string.home_move_up)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_home_card_netease_radar)).performClick()

        expand(CoreCommonR.string.settings_home_card_netease_recommended)
        switch(CoreCommonR.string.home_netease_high_quality_playlists).assertIsOn().performClick()
        move(CoreCommonR.string.home_netease_daily_playlists, CoreCommonR.string.home_move_up)
        composeRule.waitForIdle()

        runBlocking {
            verify(repository).setHomeTrendingSubSectionVisible(HomeTrendingSubSectionIds.TOP_HOT, true)
            verify(repository).setHomeRadarSubSectionVisible(HomeRadarSubSectionIds.RADAR_PLAYLISTS, false)
            verify(repository).setHomeRecommendedSubSectionVisible(HomeRecommendedSubSectionIds.HIGH_QUALITY, false)
            verify(repository).setHomeTrendingOrder(
                listOf(HomeTrendingSubSectionIds.PERSONALIZED_NEW_SONGS, HomeTrendingSubSectionIds.TOP_SOARING) +
                    HomeTrendingSubSectionIds.DEFAULT_ORDER.drop(2)
            )
            verify(repository).setHomeRadarOrder(
                listOf(HomeRadarSubSectionIds.RADAR_PLAYLISTS, HomeRadarSubSectionIds.PERSONAL_RADAR) +
                    HomeRadarSubSectionIds.DEFAULT_ORDER.drop(2)
            )
            verify(repository).setHomeRecommendedOrder(
                listOf(HomeRecommendedSubSectionIds.DAILY_RESOURCE, HomeRecommendedSubSectionIds.PERSONALIZED) +
                    HomeRecommendedSubSectionIds.DEFAULT_ORDER.drop(2)
            )
        }
    }

    @Test
    fun internationalOrderUsesItsSavedSlotsAndDisablesFirstMoveUp() {
        val ids = InternationalHomeSectionIds
        val saved = InternationalHomeLayout(order = listOf(ids.FEED, ids.SPOTLIGHT, ids.CONTINUE))
        render(initial.copy(internationalLayout = saved), international = true)
        openReorderMenu(CoreCommonR.string.international_home_feed)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.home_move_up)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.home_move_down)).performClick()
        composeRule.waitForIdle()
        runBlocking {
            verify(repository).setInternationalHomeOrder(listOf(ids.SPOTLIGHT, ids.FEED, ids.CONTINUE))
        }
    }

    @Test
    fun internationalModeOffersOnlyItsOwnVisibilitySettings() {
        val ids = InternationalHomeSectionIds
        val saved = InternationalHomeLayout(hidden = setOf(ids.FEED))
        render(initial.copy(internationalLayout = saved, showHomeContinueCard = false), international = true)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_home_card_bilibili)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_home_card_netease_trending)).assertDoesNotExist()
        switch(CoreCommonR.string.international_home_spotlight).assertIsOn().performClick()
        switch(CoreCommonR.string.player_continue).assertIsOn().performClick()
        switch(CoreCommonR.string.international_home_feed).assertIsOff().performClick()
        composeRule.waitForIdle()

        runBlocking {
            verify(repository).setInternationalHomeSectionVisible(ids.SPOTLIGHT, false)
            verify(repository).setInternationalHomeSectionVisible(ids.CONTINUE, false)
            verify(repository).setInternationalHomeSectionVisible(ids.FEED, true)
        }
    }

    private fun switch(titleRes: Int) =
        composeRule.onNode(isToggleable() and hasContentDescription(context.getString(titleRes))).performScrollTo()

    @Test
    fun internationalFeedExpandsAndWritesOnlyItsKindVisibility() {
        val kinds = InternationalHomeFeedKindIds
        val layout = InternationalHomeLayout(hiddenFeedKinds = setOf(kinds.ALBUM))
        render(initial.copy(internationalLayout = layout), international = true)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.artist_tab_songs)).assertDoesNotExist()
        expand(CoreCommonR.string.international_home_feed)
        switch(CoreCommonR.string.artist_tab_songs).assertIsOn().performClick()
        switch(CoreCommonR.string.library_netease_tab_playlists).assertIsOn()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.library_netease_tab_playlists)).performScrollTo().performClick()
        switch(CoreCommonR.string.library_netease_tab_albums).assertIsOff().performClick()
        switch(CoreCommonR.string.explore_search_type_artist).assertIsOn().performClick()
        composeRule.waitForIdle()
        runBlocking {
            verify(repository).setInternationalHomeFeedKindVisible(kinds.TRACK, false)
            verify(repository).setInternationalHomeFeedKindVisible(kinds.PLAYLIST, false)
            verify(repository).setInternationalHomeFeedKindVisible(kinds.ALBUM, true)
            verify(repository).setInternationalHomeFeedKindVisible(kinds.ARTIST, false)
        }
    }

    @Test
    fun cleanSearchReopensTheFeedAfterTheUserCollapsesIt() {
        val pulse = mutableIntStateOf(1)
        render(initial, international = true, style = UiStyle.Clean, target = "manual:international_home_feed_album") {
            pulse.intValue
        }
        switch(CoreCommonR.string.library_netease_tab_albums).assertIsOn()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_home_card_netease_radar)).assertDoesNotExist()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.international_home_feed)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.library_netease_tab_albums)).assertDoesNotExist()
        composeRule.runOnIdle { pulse.intValue++ }
        switch(CoreCommonR.string.library_netease_tab_albums).assertIsOn()
    }

    @Test
    fun cleanHiddenHomeCanRestoreARecommendationFromItsRow() {
        val sections = InternationalHomeSectionIds
        val kinds = InternationalHomeFeedKindIds
        val layout = InternationalHomeLayout(
            hidden = setOf(sections.CONTINUE, sections.SPOTLIGHT),
            hiddenFeedKinds = kinds.DEFAULT_ORDER.toSet()
        )
        render(initial.copy(internationalLayout = layout), international = true, style = UiStyle.Clean)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.settings_home_hidden_notice)).assertExists()
        expand(CoreCommonR.string.international_home_feed)
        switch(CoreCommonR.string.library_netease_tab_albums).assertIsOff()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.library_netease_tab_albums)).performScrollTo().performClick()
        composeRule.waitForIdle()
        runBlocking { verify(repository).setInternationalHomeFeedKindVisible(kinds.ALBUM, true) }
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.international_home_feed)).performScrollTo().performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.library_netease_tab_albums)).assertDoesNotExist()
    }

    private fun expand(titleRes: Int) {
        val description = context.getString(CoreCommonR.string.settings_home_sub_section_expand, context.getString(titleRes))
        composeRule.onNodeWithContentDescription(description).performScrollTo().performClick()
    }

    private fun openReorderMenu(titleRes: Int) {
        val description = context.getString(CoreCommonR.string.home_reorder_section, context.getString(titleRes))
        composeRule.onNodeWithContentDescription(description).performScrollTo().performClick()
    }

    private fun move(titleRes: Int, directionRes: Int) {
        openReorderMenu(titleRes)
        composeRule.onNodeWithText(context.getString(directionRes)).performClick()
    }

    private fun render(
        state: AppHomeCardsSettingsState,
        international: Boolean = false,
        style: UiStyle = UiStyle.Material,
        target: String? = null,
        highlightPulse: () -> Int = { 0 }
    ) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "#6750A4",
                uiStyle = style,
                animateColors = false
            ) {
                val scope = rememberCoroutineScope()
                val actions = remember(scope) { AppHomeSettingsActions(repository, scope) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsPersonalizationHomeCard(
                        internationalEnabled = international,
                        homeStartAvailable = !international || state.internationalLayout.isHomeAvailable(hasRecentUsage = false),
                        state = state,
                        actions = actions,
                        highlightTargetId = target,
                        highlightPulse = highlightPulse(),
                        onHighlightFinished = null
                    )
                }
            }
        }
    }
}
