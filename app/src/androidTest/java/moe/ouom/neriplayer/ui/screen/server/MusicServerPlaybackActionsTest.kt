package moe.ouom.neriplayer.ui.screen.server

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriDesign
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import moe.ouom.neriplayer.common.R as CoreCommonR

@RunWith(AndroidJUnit4::class)
class MusicServerPlaybackActionsTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val songs = listOf(SongItem(1, "Track", "Artist", "Album", 0, 8_000, null))

    @Before fun requireComposeHost() = assumeComposeHostAvailable()

    @Test fun cleanLightHasSeparatePlaybackActions() = verify(UiStyle.Clean, false)
    @Test fun cleanDarkHasSeparatePlaybackActions() = verify(UiStyle.Clean, true)
    @Test fun materialLightHasSeparatePlaybackActions() = verify(UiStyle.Material, false)
    @Test fun materialDarkHasSeparatePlaybackActions() = verify(UiStyle.Material, true)
    @Test fun cleanSmallEnglishKeepsBothActionsReadable() = verify(UiStyle.Clean, false, 320.dp)
    @Test fun materialSmallEnglishKeepsBothActionsReadable() = verify(UiStyle.Material, false, 320.dp)
    @Test fun cleanSmallLargeFontKeepsBothActionsReadable() = verify(UiStyle.Clean, false, 320.dp, 2f)
    @Test fun materialSmallLargeFontKeepsBothActionsReadable() = verify(UiStyle.Material, true, 320.dp, 2f)
    @Test fun cleanChineseLargeFontKeepsBothActionsReadable() = verify(UiStyle.Clean, false, 320.dp, 2f, chinese = true)
    @Test fun materialChineseLargeFontKeepsBothActionsReadable() = verify(UiStyle.Material, false, 320.dp, 2f, chinese = true)
    @Test fun cleanEmptyListDisablesBothActions() = verify(UiStyle.Clean, false, empty = true)
    @Test fun materialEmptyListDisablesBothActions() = verify(UiStyle.Material, false, empty = true)

    private fun verify(style: UiStyle, dark: Boolean, width: Dp = 360.dp, fontScale: Float = 1f,
                       empty: Boolean = false, chinese: Boolean = false) {
        val localized = if (chinese) context.createConfigurationContext(Configuration(context.resources.configuration).apply {
            setLocale(Locale.SIMPLIFIED_CHINESE)
        }) else context
        val events = mutableListOf<String>()
        var hostView: android.view.View? = null
        composeRule.setContent {
            hostView = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale),
                LocalContext provides localized, LocalConfiguration provides localized.resources.configuration) {
                NeriTheme(followSystemDark = false, forceDark = dark, dynamicColor = false,
                    seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Box(Modifier.width(width).padding(horizontal = NeriDesign.tokens.pageHorizontalPadding)
                                .testTag("serverPlaybackActions")) {
                                ServerSongActions(if (empty) emptyList() else songs, 0, false, false, false,
                                    onPlay = { events += "ordered" }, onAddToPlaylist = {}, onDownload = {},
                                    onSelection = {}, onSelectAll = {}, onShufflePlay = { events += "shuffle" })
                            }
                        }
                    }
                }
            }
        }
        val playLabel = localized.getString(CoreCommonR.string.player_play_all)
        val shuffleLabel = localized.getString(CoreCommonR.string.player_shuffle_play)
        val play = composeRule.onNodeWithText(playLabel).assertIsDisplayed()
        val shuffle = composeRule.onNodeWithText(shuffleLabel).assertIsDisplayed()
        val bounds = composeRule.onNodeWithTag("serverPlaybackActions").fetchSemanticsNode().boundsInRoot
        val playBounds = play.fetchSemanticsNode().boundsInRoot
        val shuffleBounds = shuffle.fetchSemanticsNode().boundsInRoot
        assertFalse("playback actions must not overlap", playBounds.overlaps(shuffleBounds))
        if (width == 360.dp && fontScale == 1f) {
            assertEquals("normal text keeps both actions side by side", playBounds.top, shuffleBounds.top, 1f)
            assertEquals("adjacent actions have equal width", playBounds.width, shuffleBounds.width, 1f)
        }
        if (InstrumentationRegistry.getArguments().getString("captureMusicServerUi") == "true") {
            composeRule.captureMusicServerUi("music-server-playback-${style.storageValue}-$dark-$fontScale-$empty-$chinese.png",
                checkNotNull(hostView))
        }
        for (label in listOf(playLabel, shuffleLabel)) {
            val text = composeRule.onNodeWithText(label, useUnmergedTree = true)
            val layouts = mutableListOf<TextLayoutResult>()
            text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            // 语义树重建的段落可能包含额外空白，按实际字符和行边界判断裁切
            assertEquals(label, layout.layoutInput.text.text)
            assertEquals("playback labels stay on one complete line", 1, layout.lineCount)
            assertFalse("playback labels must not be ellipsized", layout.isLineEllipsized(0))
            assertEquals("every label character must remain visible", label.length, layout.getLineEnd(0, visibleEnd = true))
            assertTrue("label glyphs fit horizontally", layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.size.width + 1f)
            assertTrue("label glyphs fit vertically", layout.getLineTop(0) >= -1f && layout.getLineBottom(0) <= layout.size.height + 1f)
            val button = composeRule.onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            assertTrue("actions stay inside page padding", button.left >= bounds.left - 1 && button.right <= bounds.right + 1)
        }
        if (empty) {
            play.assertIsNotEnabled()
            shuffle.assertIsNotEnabled()
        } else {
            shuffle.performClick()
            play.performClick()
            composeRule.runOnIdle { assertEquals(listOf("shuffle", "ordered"), events) }
        }
    }
}
