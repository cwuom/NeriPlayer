package moe.ouom.neriplayer.ui.screen.tab.settings.appearance

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.settings.route.AppHomeCardsSettingsState
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import moe.ouom.neriplayer.ui.settings.route.HomeSectionSettingsActions
import org.mockito.Mockito.mock
import org.mockito.Mockito.doAnswer
import moe.ouom.neriplayer.data.model.settings.home.HomeMajorSectionIds
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-w360dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsHomeCardInternationalizationTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun cleanHidesBilibiliInInternationalModeAndRestoresItsSwitchState() = verifySwitch(UiStyle.Clean)

    @Test
    fun materialHidesBilibiliInInternationalModeAndRestoresItsSwitchState() = verifySwitch(UiStyle.Material)

    private fun verifySwitch(style: UiStyle) {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val label = application.getString(CoreCommonR.string.settings_home_card_bilibili)
        var internationalEnabled by mutableStateOf(false)
        var checked by mutableStateOf(true)
        val changes = mutableListOf<Boolean>()
        val actions = mock(HomeSectionSettingsActions::class.java)
        doAnswer {
            checked = it.getArgument<Boolean>(1)
            changes += checked
            Unit
        }.`when`(actions).setMajorVisible(HomeMajorSectionIds.BILIBILI, false)
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
                        internationalEnabled = internationalEnabled,
                        homeStartAvailable = true,
                        state = AppHomeCardsSettingsState(true, true, true, true, checked),
                        actions = actions,
                        highlightTargetId = null,
                        highlightPulse = 0,
                        onHighlightFinished = null
                    )
                }
            }
        }

        val row = composeRule.onNodeWithText(label)
        val switch = row.onChildren().filterToOne(isToggleable())
        row.performScrollTo()
        switch.assertIsOn()
        composeRule.runOnIdle { internationalEnabled = true }
        row.assertDoesNotExist()
        composeRule.runOnIdle { internationalEnabled = false }
        row.performScrollTo()
        switch.assertIsOn()
        row.performClick()
        switch.assertIsOff()
        composeRule.runOnIdle { internationalEnabled = true }
        row.assertDoesNotExist()
        composeRule.runOnIdle { internationalEnabled = false }
        row.performScrollTo()
        switch.assertIsOff()
        assertEquals(listOf(false), changes)
    }
}
