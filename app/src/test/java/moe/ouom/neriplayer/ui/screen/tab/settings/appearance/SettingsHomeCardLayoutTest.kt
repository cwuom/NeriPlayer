package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.model.settings.home.HomeRadarSubSectionIds
import moe.ouom.neriplayer.ui.screen.tab.settings.home.settingsHomeCardCopy
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w600dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeCardLayoutTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun cleanExpandsFromTheTitleAndAlignsVisibilitySwitches() = verifyLayout(UiStyle.Clean)

    @Test
    fun materialExpandsFromTheTitleAndAlignsVisibilitySwitches() = verifyLayout(UiStyle.Material)

    private fun verifyLayout(style: UiStyle) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val copy = settingsHomeCardCopy(false)
        val radarTitle = context.getString(copy.radarLabelRes)
        val dailyTitle = context.getString(CoreCommonR.string.home_netease_daily_songs)
        val expand = context.getString(CoreCommonR.string.settings_home_sub_section_expand, radarTitle)
        val parentChanges = mutableListOf<Boolean>()
        val childChanges = mutableListOf<Pair<String, Boolean>>()

        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "#6750A4",
                uiStyle = style,
                animateColors = false
            ) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsPersonalizationHomeCard(
                        internationalEnabled = false,
                        homeTrendingLabelRes = copy.trendingLabelRes,
                        homeRadarLabelRes = copy.radarLabelRes,
                        homeRecommendedLabelRes = copy.recommendedLabelRes,
                        homeStartAvailable = true,
                        showHomeContinueCard = true,
                        onShowHomeContinueCardChange = {},
                        showHomeTrendingCard = true,
                        onShowHomeTrendingCardChange = {},
                        showHomeRadarCard = true,
                        onShowHomeRadarCardChange = { parentChanges += it },
                        showHomeRecommendedCard = true,
                        onShowHomeRecommendedCardChange = {},
                        showHomeBilibiliCard = true,
                        onShowHomeBilibiliCardChange = {},
                        onSetRadarVisible = { id, checked -> childChanges += id to checked },
                        highlightTargetId = null,
                        highlightPulse = 0,
                        onHighlightFinished = null
                    )
                }
            }
        }

        val label = composeRule.onNodeWithText(radarTitle, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val arrow = composeRule.onNodeWithContentDescription(expand, useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertTrue("Disclosure arrow should immediately follow its label", arrow.left - label.right in 0f..24f)

        composeRule.onNodeWithContentDescription(expand).performClick()
        val parent = composeRule.onNode(isToggleable() and hasContentDescription(radarTitle))
        val child = composeRule.onNode(isToggleable() and hasContentDescription(dailyTitle))
        parent.assertIsOn()
        child.assertIsOn()
        assertTrue(parentChanges.isEmpty())
        assertEquals(
            parent.fetchSemanticsNode().boundsInRoot.right,
            child.fetchSemanticsNode().boundsInRoot.right,
            1f
        )

        child.performClick()
        assertEquals(listOf(HomeRadarSubSectionIds.DAILY_RECOMMEND to false), childChanges)
        composeRule.onNodeWithText(radarTitle).performClick()
        child.assertDoesNotExist()
        assertTrue(parentChanges.isEmpty())
        parent.performClick()
        assertEquals(listOf(false), parentChanges)
    }
}
