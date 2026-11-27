package moe.ouom.neriplayer.ui.screen.history.stats

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.data.stats.PlaybackStatsRank
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "zh-rCN-w320dp-h640dp")
class StatsRankDetailDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test fun cleanAlbumShowsItsRankAndFullDurationOnANarrowScreen() = checkDetails(UiStyle.Clean)
    @Test fun materialArtistShowsItsRankAndFullDurationOnANarrowScreen() = checkDetails(UiStyle.Material)

    private fun checkDetails(style: UiStyle) {
        val rank = PlaybackStatsRank("I Look To You", 1, 3_636_000, 19, 0.1922)
        val album = style == UiStyle.Clean
        compose.setContent {
            var opened by remember { mutableStateOf(true) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.45f)) {
                NeriTheme(followSystemDark = false, forceDark = true, dynamicColor = false,
                    seedColorHex = "#665540", uiStyle = style, animateColors = false) {
                    if (opened) StatsRankDetailDialog(rank, 2, album, offlineMode = true) { opened = false }
                }
            }
        }
        compose.onNodeWithText(if (album) "专辑 · 第 2 名" else "艺人 · 第 2 名").assertExists()
        compose.onNodeWithText(rank.name).assertExists()
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("1 小时 0 分 36 秒").performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertEquals(1, layouts.single().lineCount)
        assertFalse(layouts.single().didOverflowWidth)
        compose.onNodeWithText("19.22%").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithTag("statsRankDetail").assertDoesNotExist()
    }
}
