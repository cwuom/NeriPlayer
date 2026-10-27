package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaArtistInfo
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.FittedTestViewport
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.R as AppR

@RunWith(AndroidJUnit4::class)
class SongEncyclopediaArtistBriefTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val width = mutableStateOf(320)
    private val fontScale = mutableStateOf(1f)
    private val brief = mutableStateOf(SHORT_BRIEF)

    @Before
    fun prepare() = assumeComposeHostAvailable()

    @Test
    fun cleanLightCompleteBriefHasNoExpandAction() = checkCompleteBrief(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkCompleteBriefHasNoExpandAction() = checkCompleteBrief(UiStyle.Clean, dark = true)

    @Test
    fun materialLightCompleteBriefHasNoExpandAction() = checkCompleteBrief(UiStyle.Material, dark = false)

    @Test
    fun materialDarkCompleteBriefHasNoExpandAction() = checkCompleteBrief(UiStyle.Material, dark = true)

    @Test
    fun cleanLightOverflowingBriefCanExpandAndCollapse() = checkOverflowingBrief(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkOverflowingBriefCanExpandAndCollapse() = checkOverflowingBrief(UiStyle.Clean, dark = true)

    @Test
    fun materialLightOverflowingBriefCanExpandAndCollapse() = checkOverflowingBrief(UiStyle.Material, dark = false)

    @Test
    fun materialDarkOverflowingBriefCanExpandAndCollapse() = checkOverflowingBrief(UiStyle.Material, dark = true)

    @Test
    fun widthChangesReevaluateWhetherCollapsedBriefNeedsExpandAction() {
        brief.value = WRAPPING_BRIEF
        width.value = 440
        render(UiStyle.Clean, dark = false)
        assertCompleteBriefHasNoAction()

        composeRule.runOnIdle { width.value = 160 }
        assertTrue(textLayout().hasVisualOverflow)
        action(AppR.string.encyclopedia_action_expand).assertIsDisplayed()

        composeRule.runOnIdle { width.value = 440 }
        assertCompleteBriefHasNoAction()
    }

    @Test
    fun fontScaleChangesReevaluateWhetherCollapsedBriefNeedsExpandAction() {
        brief.value = WRAPPING_BRIEF
        render(UiStyle.Material, dark = true)
        assertCompleteBriefHasNoAction()

        composeRule.runOnIdle { fontScale.value = 2.2f }
        assertTrue(textLayout().hasVisualOverflow)
        action(AppR.string.encyclopedia_action_expand).performScrollTo().assertIsDisplayed()

        composeRule.runOnIdle { fontScale.value = 1f }
        assertCompleteBriefHasNoAction()
    }

    @Test
    fun expandedBriefHidesTheActionWhenWiderLayoutNoLongerNeedsTruncation() {
        brief.value = WRAPPING_BRIEF
        width.value = 160
        render(UiStyle.Clean, dark = false)
        assertTrue(textLayout().hasVisualOverflow)
        action(AppR.string.encyclopedia_action_expand).performScrollTo().performClick()

        composeRule.runOnIdle { width.value = 440 }
        assertCompleteBriefHasNoAction()

        composeRule.runOnIdle { width.value = 160 }
        assertFalse(textLayout().hasVisualOverflow)
        assertTrue(textLayout().lineCount > 4)
        action(AppR.string.encyclopedia_action_collapse).performScrollTo().performClick()
        assertTrue(textLayout().hasVisualOverflow)
    }

    @Test
    fun expandedBriefKeepsCollapseActionWhenWidthChangesAndStillExceedsFourLines() {
        brief.value = LONG_BRIEF
        render(UiStyle.Clean, dark = true)
        action(AppR.string.encyclopedia_action_expand).performScrollTo().performClick()

        composeRule.runOnIdle { width.value = 240 }
        assertFalse(textLayout().hasVisualOverflow)
        assertTrue(textLayout().lineCount > 4)
        action(AppR.string.encyclopedia_action_collapse).performScrollTo().performClick()
        assertEquals(4, textLayout().lineCount)
        assertTrue(textLayout().hasVisualOverflow)
    }

    @Test
    fun replacingExpandedBriefWithCompleteTextRemovesTheAction() {
        brief.value = LONG_BRIEF
        render(UiStyle.Material, dark = false)
        action(AppR.string.encyclopedia_action_expand).performScrollTo().performClick()

        composeRule.runOnIdle { brief.value = SHORT_BRIEF }
        assertCompleteBriefHasNoAction()
    }

    private fun checkCompleteBrief(style: UiStyle, dark: Boolean) {
        render(style, dark)
        assertCompleteBriefHasNoAction()
        composeRule.onNodeWithText(SHORT_BRIEF, useUnmergedTree = true).assertIsDisplayed()
    }

    private fun checkOverflowingBrief(style: UiStyle, dark: Boolean) {
        brief.value = LONG_BRIEF
        render(style, dark)
        assertTrue(textLayout().hasVisualOverflow)
        assertEquals(4, textLayout().lineCount)
        action(AppR.string.encyclopedia_action_expand).performScrollTo().performClick()
        assertFalse(textLayout().hasVisualOverflow)
        assertEquals(6, textLayout().lineCount)
        action(AppR.string.encyclopedia_action_collapse).performScrollTo().performClick()
        assertTrue(textLayout().hasVisualOverflow)
        assertEquals(4, textLayout().lineCount)
        action(AppR.string.encyclopedia_action_expand).assertExists()
    }

    private fun assertCompleteBriefHasNoAction() {
        assertFalse(textLayout().hasVisualOverflow)
        assertTrue(textLayout().lineCount <= 4)
        action(AppR.string.encyclopedia_action_expand).assertDoesNotExist()
        action(AppR.string.encyclopedia_action_collapse).assertDoesNotExist()
    }

    private fun render(style: UiStyle, dark: Boolean) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = dark,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false
            ) {
                FittedTestViewport(width.value.dp, 640.dp, fontScale = fontScale.value) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        SongEncyclopediaSectionCard(
                            titleRes = AppR.string.encyclopedia_section_artist,
                            icon = Icons.Outlined.Person
                        ) {
                            SongEncyclopediaArtistBrief(
                                artist = EncyclopediaArtistInfo(
                                    artistId = 9_541_000_002L,
                                    name = "测试艺人",
                                    briefDesc = brief.value
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun textLayout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(brief.value, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
        return results.single()
    }

    private fun action(resourceId: Int) = composeRule.onNodeWithText(context.getString(resourceId))

    private companion object {
        const val SHORT_BRIEF = "这是一段完整显示的艺人介绍"
        const val LONG_BRIEF = "艺人介绍第一行\n艺人介绍第二行\n艺人介绍第三行\n艺人介绍第四行\n艺人介绍第五行\n艺人介绍最后一行"
        const val WRAPPING_BRIEF = "艺人介绍会随页面宽度和字号重新排版，只有超过四行时才提供展开入口，展开以后依然能够收起，宽屏下完整显示就隐藏按钮"
    }
}
