package moe.ouom.neriplayer.ui.screen.tab.settings.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.settings.SettingsRepository
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.screen.tab.settings.appearance.SettingsPersonalizationHomeCard
import moe.ouom.neriplayer.ui.settings.route.AppHomeSettingsActions
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsHomeBilibiliVisibilityTest {
    @get:Rule val composeRule = createComposeRule()

    @Before
    fun prepare() = assumeComposeHostAvailable()

    @Test
    fun cleanHomeSwitchPersistsAndUpdatesHomeAvailability() = verifySwitch(UiStyle.Clean)

    @Test
    fun materialHomeSwitchPersistsAndUpdatesHomeAvailability() = verifySwitch(UiStyle.Material)

    private fun verifySwitch(style: UiStyle) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = SettingsRepository(context)
        val original = runBlocking { repository.homeCardBilibiliFlow.first() }
        val label = context.getString(CoreCommonR.string.settings_home_card_bilibili)
        val hiddenNotice = context.getString(CoreCommonR.string.settings_home_hidden_notice)
        try {
            runBlocking { repository.setHomeCardBilibili(true) }
            composeRule.setContent {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "#6750A4",
                    uiStyle = style,
                    animateColors = false
                ) {
                    val enabled by repository.homeCardBilibiliFlow.collectAsState(initial = true)
                    val scope = rememberCoroutineScope()
                    val actions = AppHomeSettingsActions(repository, scope)
                    val copy = settingsHomeCardCopy(false)
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        SettingsPersonalizationHomeCard(
                            internationalEnabled = false,
                            homeTrendingLabelRes = copy.trendingLabelRes,
                            homeRadarLabelRes = copy.radarLabelRes,
                            homeRecommendedLabelRes = copy.recommendedLabelRes,
                            homeTrendingSupportingRes = copy.trendingSupportingRes,
                            homeRadarSupportingRes = copy.radarSupportingRes,
                            homeRecommendedSupportingRes = copy.recommendedSupportingRes,
                            homeStartAvailable = isSettingsHomeStartAvailable(false, false, false, false, false, enabled),
                            showHomeContinueCard = false,
                            onShowHomeContinueCardChange = {},
                            showHomeTrendingCard = false,
                            onShowHomeTrendingCardChange = {},
                            showHomeRadarCard = false,
                            onShowHomeRadarCardChange = {},
                            showHomeRecommendedCard = false,
                            onShowHomeRecommendedCardChange = {},
                            showHomeBilibiliCard = enabled,
                            onShowHomeBilibiliCardChange = actions.onShowHomeBilibiliCardChange,
                            highlightTargetId = null,
                            highlightPulse = 0,
                            onHighlightFinished = null
                        )
                    }
                }
            }
            composeRule.onNodeWithText(hiddenNotice).assertDoesNotExist()
            composeRule.onNodeWithText(label).performScrollTo().performClick()
            composeRule.waitUntil(5000) { !runBlocking { repository.homeCardBilibiliFlow.first() } }
            composeRule.onNodeWithText(hiddenNotice).performScrollTo().assertExists()
            assertFalse(runBlocking { SettingsRepository(context).homeCardBilibiliFlow.first() })

            composeRule.onNodeWithText(label).performScrollTo().performClick()
            composeRule.waitUntil(5000) { runBlocking { repository.homeCardBilibiliFlow.first() } }
            composeRule.onNodeWithText(hiddenNotice).assertDoesNotExist()
            assertTrue(runBlocking { repository.homeCardBilibiliFlow.first() })
        } finally {
            runBlocking { repository.setHomeCardBilibili(original) }
        }
    }
}
