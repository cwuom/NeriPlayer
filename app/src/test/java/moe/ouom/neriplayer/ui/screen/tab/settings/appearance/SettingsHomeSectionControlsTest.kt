package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeRecommendedSubSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeSongSectionIds
import moe.ouom.neriplayer.data.model.settings.home.HomeTrendingSubSectionIds
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.ui.screen.tab.settings.home.settingsHomeCardCopy
import moe.ouom.neriplayer.ui.screen.tab.settings.page.SettingsHighlightState
import moe.ouom.neriplayer.ui.settings.route.AppHomeCardsSettingsState
import moe.ouom.neriplayer.ui.settings.route.AppHomeSettingsActions
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
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
        val copy = settingsHomeCardCopy(false)
        switch(CoreCommonR.string.player_continue).assertIsOn().performClick()
        switch(copy.trendingLabelRes).assertIsOn().performClick()
        switch(copy.radarLabelRes).assertIsOff().performClick()
        switch(copy.recommendedLabelRes).assertIsOff().performClick()
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
        val copy = settingsHomeCardCopy(false)
        render(initial.copy(trendingHidden = setOf(HomeTrendingSubSectionIds.TOP_HOT)))

        expand(copy.trendingLabelRes)
        switch(CoreCommonR.string.home_netease_hot_rank).assertIsOff().performClick()
        move(CoreCommonR.string.recommend_trending, CoreCommonR.string.home_move_down)
        composeRule.onNodeWithText(context.getString(copy.trendingLabelRes)).performClick()

        expand(copy.radarLabelRes)
        switch(CoreCommonR.string.home_netease_radar_playlists).assertIsOn().performClick()
        move(CoreCommonR.string.home_netease_radar_playlists, CoreCommonR.string.home_move_up)
        composeRule.onNodeWithText(context.getString(copy.radarLabelRes)).performClick()

        expand(copy.recommendedLabelRes)
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
    fun internationalReorderKeepsTheHiddenBilibiliSlotAndDisablesFirstMoveUp() {
        val order = listOf(
            HomeMajorSectionIds.CONTINUE, HomeMajorSectionIds.BILIBILI, HomeMajorSectionIds.RADAR,
            HomeMajorSectionIds.TRENDING, HomeMajorSectionIds.RECOMMENDED
        )
        render(initial.copy(majorOrder = order), international = true)
        openReorderMenu(CoreCommonR.string.player_continue)
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.home_move_up)).assertIsNotEnabled()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.home_move_down)).performClick()
        composeRule.waitForIdle()
        runBlocking {
            verify(repository).setHomeMajorOrder(
                listOf(
                    HomeMajorSectionIds.RADAR, HomeMajorSectionIds.BILIBILI, HomeMajorSectionIds.CONTINUE,
                    HomeMajorSectionIds.TRENDING, HomeMajorSectionIds.RECOMMENDED
                )
            )
        }
    }

    @Test
    fun songSectionModeTargetExpandsTheGroupsThatOwnSongSubSections() {
        val groups = setOf(HomeMajorSectionIds.RADAR, HomeMajorSectionIds.TRENDING)
        assertEquals(
            groups,
            homeSectionGroupsToExpand("setting:home_song_section_mode", groups)
        )
    }

    @Test
    fun otherTargetsDoNotForceAnyGroupToExpand() {
        val groups = setOf(HomeMajorSectionIds.RADAR, HomeMajorSectionIds.TRENDING)
        assertEquals(
            emptySet<String>(),
            homeSectionGroupsToExpand("setting:home_song_card_columns", groups)
        )
        assertEquals(
            emptySet<String>(),
            homeSectionGroupsToExpand(null, groups)
        )
    }

    @Test
    fun searchingSongSectionModeExpandsTheGroupSoTheSelectorIsReachable() {
        render(initial, highlightTargetId = "setting:home_song_section_mode")
        composeRule.waitForIdle()

        // 分组默认折叠时形态选择器不在组合里；自动展开后每个歌曲子模块行都会出现。
        composeRule.onAllNodesWithText(
            context.getString(CoreCommonR.string.settings_home_song_section_mode_card)
        ).assertCountEquals(HomeSongSectionIds.ALL.size)
    }

    private fun switch(titleRes: Int) =
        composeRule.onNode(isToggleable() and hasContentDescription(context.getString(titleRes))).performScrollTo()

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
        highlightTargetId: String? = null
    ) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "#6750A4",
                uiStyle = UiStyle.Material,
                animateColors = false
            ) {
                val scope = rememberCoroutineScope()
                val actions = remember(scope) { AppHomeSettingsActions(repository, scope) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsPersonalizationHomeCard(
                        internationalEnabled = international,
                        copy = settingsHomeCardCopy(international),
                        homeStartAvailable = true,
                        state = state,
                        actions = actions,
                        highlight = SettingsHighlightState(
                            targetId = highlightTargetId,
                            pulse = if (highlightTargetId == null) 0 else 1,
                            onFinished = null
                        )
                    )
                }
            }
        }
    }
}
