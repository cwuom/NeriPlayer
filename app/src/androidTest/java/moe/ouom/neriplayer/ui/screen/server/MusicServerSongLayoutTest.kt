package moe.ouom.neriplayer.ui.screen.server

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriTheme
import moe.ouom.neriplayer.util.format.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServerSongLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun requireComposeHost() = assumeComposeHostAvailable()

    @Test fun cleanLightKeepsTitleWidthAndDuration() = verify(UiStyle.Clean, false)
    @Test fun cleanDarkKeepsTitleWidthAndDuration() = verify(UiStyle.Clean, true)
    @Test fun materialLightKeepsTitleWidthAndDuration() = verify(UiStyle.Material, false)
    @Test fun materialDarkKeepsTitleWidthAndDuration() = verify(UiStyle.Material, true)
    @Test fun cleanSmallLargeFontKeepsMetadataAndMenuApart() = verify(UiStyle.Clean, false, 320.dp, 2f)
    @Test fun materialSmallLargeFontKeepsMetadataAndMenuApart() = verify(UiStyle.Material, true, 320.dp, 2f)

    private fun verify(style: UiStyle, dark: Boolean, width: Dp = 360.dp, fontScale: Float = 1f) {
        var favorites = 0
        var hostView: android.view.View? = null
        composeRule.setContent {
            hostView = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(followSystemDark = false, forceDark = dark, dynamicColor = false,
                    seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.width(width).padding(horizontal = NeriDesign.tokens.pageHorizontalPadding)
                                .testTag("serverSongContent")) {
                                MusicServerSongRow(song, 65, current = false, playing = false,
                                    favorite = true, favoriteBusy = false, selectionMode = false, selected = false,
                                    downloaded = true, onClick = {}, onSelect = {}, onFavorite = { favorites++ },
                                    onAddToPlaylist = {}, onPlayNext = {}, onAddToQueue = {}, onDownload = {}, offlineMode = true)
                            }
                        }
                    }
                }
            }
        }
        val content = composeRule.onNodeWithTag("serverSongContent").fetchSemanticsNode().boundsInRoot
        val title = composeRule.onNodeWithText(song.name, useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val duration = composeRule.onNodeWithText(formatDuration(song.durationMs), useUnmergedTree = true)
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val downloaded = composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.downloaded),
            useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val menu = composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions),
            useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue("song title keeps at least half of the padded row width", title.width >= content.width * 0.5f)
        assertTrue("duration belongs below the song title", duration.top >= title.bottom - 1f)
        assertTrue("song title does not enter the menu", title.right <= menu.left + 1f)
        assertTrue("duration does not enter the menu", duration.right <= menu.left + 1f)
        assertFalse("download mark and duration remain separate", downloaded.overlaps(duration))
        assertTrue("menu stays inside the page inset", menu.right <= content.right + 1f)
        if (InstrumentationRegistry.getArguments().getString("captureMusicServerUi") == "true") {
            composeRule.captureMusicServerUi("server-song-${style.storageValue}-$dark-$fontScale.png", checkNotNull(hostView))
        }
        composeRule.onNodeWithContentDescription(context.getString(CoreCommonR.string.cd_more_actions)).performClick()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.favorite_remove)).performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(1, favorites) }
    }

    private val song = SongItem(1L, "PR511 Track title with a long album edition and live session",
        "An artist with a long collaboration credit", "Album", 0L, 225_000L, null)
}
