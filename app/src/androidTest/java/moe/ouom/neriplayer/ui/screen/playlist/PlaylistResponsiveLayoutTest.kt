package moe.ouom.neriplayer.ui.screen.playlist

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaylistResponsiveLayoutTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun checkHeader(style: UiStyle, search: Boolean) {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = style,
                    animateColors = false
                ) {
                    Column(Modifier.width(300.dp).verticalScroll(rememberScrollState())) {
                        Box(Modifier.testTag("hero")) {
                            PlaylistModernHeroHeader(
                                displayName = TITLE,
                                coverUrl = null,
                                subtitle = SUBTITLE,
                                offlineMode = true,
                                height = if (search) PlaylistModernHeroSearchHeight else PlaylistModernHeroHeight,
                                actions = if (search) {
                                    { Box(Modifier.fillMaxWidth().height(60.dp).testTag("search")) }
                                } else null
                            )
                        }
                        Box(Modifier.fillMaxWidth().height(60.dp).testTag("next"))
                    }
                }
            }
        }
        listOf(TITLE, SUBTITLE).forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            composeRule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
            assertTrue(results.isNotEmpty())
            val layout = results.single()
            assertTrue(
                "visible header lines must fit vertically",
                layout.getLineBottom(layout.lineCount - 1) <= layout.size.height + 1f
            )
        }
        val hero = composeRule.onNodeWithTag("hero").getUnclippedBoundsInRoot()
        val title = composeRule.onNodeWithText(TITLE).getUnclippedBoundsInRoot()
        val subtitle = composeRule.onNodeWithText(SUBTITLE).getUnclippedBoundsInRoot()
        assertTrue("title must stay inside the hero", title.bottom <= hero.bottom)
        assertTrue("metadata must stay inside the hero", subtitle.bottom <= hero.bottom)
        if (search) {
            val field = composeRule.onNodeWithTag("search").getUnclippedBoundsInRoot()
            val next = composeRule.onNodeWithTag("next").getUnclippedBoundsInRoot()
            assertTrue("search must not overlap metadata", field.top >= subtitle.bottom)
            assertTrue("search must not overlap the following actions", field.bottom <= next.top)
        }
    }

    private fun checkActions(style: UiStyle) {
        val events = mutableListOf<String>()
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                NeriTheme(
                    followSystemDark = false,
                    forceDark = false,
                    dynamicColor = false,
                    seedColorHex = "885522",
                    uiStyle = style,
                    animateColors = false
                ) {
                    Column(Modifier.width(300.dp)) {
                        PlaylistModernHeroHeader(
                            displayName = TITLE,
                            coverUrl = null,
                            subtitle = SUBTITLE,
                            offlineMode = true,
                            height = PlaylistModernHeroHeight
                        )
                        PlaylistModernPlaybackActions(
                            songCount = 20,
                            shuffleEnabled = false,
                            repeatMode = 0,
                            onPlayInOrder = { events += "play" },
                            onShufflePlay = { events += "shufflePlay" },
                            onToggleShuffle = { events += "shuffle" },
                            onCycleRepeatMode = { events += "repeat" },
                            onExportToLocalPlaylist = { events += "export" }
                        )
                    }
                }
            }
        }
        val play = context.getString(playlistPlayLabelRes(false))
        val results = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(play, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        assertFalse("primary action must remain readable", results.single().isLineEllipsized(0))
        composeRule.onNodeWithContentDescription(play).performClick()
        composeRule.onNodeWithContentDescription(context.getString(playlistShuffleModeLabelRes(false))).performClick()
        composeRule.onNodeWithContentDescription(context.getString(playlistRepeatModeLabelRes(0))).performClick()
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.playlist_export_to_local)).performClick()
        composeRule.runOnIdle { assertEquals(listOf("play", "shuffle", "repeat", "export"), events) }
        if (style == UiStyle.Clean) {
            File(context.cacheDir, "playlist-responsive-clean-2x.png").outputStream().use {
                composeRule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun cleanHeaderFitsLargeText() = checkHeader(UiStyle.Clean, search = false)

    @Test
    fun materialHeaderFitsLargeText() = checkHeader(UiStyle.Material, search = false)

    @Test
    fun cleanHeaderKeepsSearchBelowLargeText() = checkHeader(UiStyle.Clean, search = true)

    @Test
    fun materialHeaderKeepsSearchBelowLargeText() = checkHeader(UiStyle.Material, search = true)

    @Test
    fun cleanPlaybackActionsStayReadableAndClickable() = checkActions(UiStyle.Clean)

    @Test
    fun materialPlaybackActionsStayReadableAndClickable() = checkActions(UiStyle.Material)

    private companion object {
        const val TITLE = "漫长夜晚的纯音乐精选合集"
        const val SUBTITLE = "31 首歌曲 · 播放 27 次"
    }
}
