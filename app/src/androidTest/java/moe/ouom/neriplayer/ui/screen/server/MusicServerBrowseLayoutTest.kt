package moe.ouom.neriplayer.ui.screen.server

import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import moe.ouom.neriplayer.platform.subsonic.repository.ServerLibraryCategory
import moe.ouom.neriplayer.testutil.assumeComposeHostAvailable
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MusicServerBrowseLayoutTest {
    @get:Rule val composeRule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var hostView: View

    @Before
    fun requireComposeHost() = assumeComposeHostAvailable()

    @Test
    fun cleanLightBrowseAndActions() = verify(UiStyle.Clean, dark = false)

    @Test
    fun cleanDarkBrowseAndActions() = verify(UiStyle.Clean, dark = true)

    @Test
    fun materialLightBrowseAndActions() = verify(UiStyle.Material, dark = false)

    @Test
    fun materialDarkBrowseAndActions() = verify(UiStyle.Material, dark = true)

    @Test
    fun cleanNarrowLargeFontDoesNotOverlap() = verify(UiStyle.Clean, dark = true, fontScale = 1.5f, width = 320.dp)

    @Test
    fun cleanNarrowSelectionKeepsActionsReachable() = verify(UiStyle.Clean, dark = false,
        fontScale = 1.5f, width = 320.dp, selection = true)

    @Test
    fun materialLandscapeShortViewportScrollsToActions() = verify(UiStyle.Material, dark = false,
        fontScale = 1.3f, width = 640.dp, height = 280.dp)

    private fun verify(style: UiStyle, dark: Boolean, fontScale: Float = 1f, width: Dp = 360.dp,
        height: Dp = 640.dp, selection: Boolean = false) {
        val song = SongItem(1L, LONG_TITLE, LONG_ARTIST, "Album", 0L, 180_000L, null)
        val profile = SubsonicProfile("preview", "Home music server with a long name", "https://music.example.com/", "listener")
        composeRule.setContent {
            hostView = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
                NeriTheme(followSystemDark = false, forceDark = dark, dynamicColor = false,
                    seedColorHex = "6750A4", uiStyle = style, animateColors = false) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                            Column(Modifier.widthIn(max = width).heightIn(max = height).testTag(ROOT)
                                .verticalScroll(rememberScrollState()).padding(16.dp)) {
                                ServerBrowserHeader(LONG_TITLE, false, true, {}, {}, null)
                                ServerProfileSelector(listOf(profile), profile.id, {})
                                ServerLibraryTabs(ServerLibraryCategory.SONGS, {})
                                ServerSearchField("", "Search songs, albums or artists", {}, {}, {})
                                ServerSongActions(listOf(song), if (selection) 1 else 0, selection, selection, true, {}, {}, {}, {}, {})
                            }
                        }
                    }
                }
            }
        }
        val root = composeRule.onNodeWithTag(ROOT).fetchSemanticsNode().boundsInRoot
        val download = composeRule.onNodeWithContentDescription(text(CoreCommonR.string.download_manager))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val refresh = composeRule.onNodeWithContentDescription(text(CoreCommonR.string.server_refresh))
            .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertFalse("header controls must not overlap", download.overlaps(refresh))
        assertTrue("header actions must remain inside the viewport", download.left >= root.left && refresh.right <= root.right)
        val stage = "${style.name.lowercase()}-${if (dark) "dark" else "light"}-${width.value.toInt()}-${height.value.toInt()}-$fontScale-$selection"
        capture("music-server-browse-$stage.png")

        composeRule.onNodeWithContentDescription(text(CoreCommonR.string.cd_more_actions))
            .performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText(text(CoreCommonR.string.download_to_local)).performScrollTo().assertIsDisplayed()
        capture("music-server-actions-$stage.png")
    }

    private fun capture(fileName: String) {
        if (InstrumentationRegistry.getArguments().getString("captureMusicServerUi") != "true") return
        composeRule.captureMusicServerUi(fileName, hostView)
    }

    private fun text(resource: Int) = context.getString(resource)

    private companion object {
        const val ROOT = "music-server-browse-preview"
        const val LONG_TITLE = "A very long album title with many words and a live recording edition"
        const val LONG_ARTIST = "An artist name and another artist with an unusually long collaboration credit"
    }
}
