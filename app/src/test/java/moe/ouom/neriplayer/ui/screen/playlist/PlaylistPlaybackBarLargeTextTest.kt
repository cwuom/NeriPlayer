package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.common.R as CoreCommonR
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 需要真实字体度量才能判断主按钮文字是否放得下 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlaylistPlaybackBarLargeTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `large text moves the play pill onto its own row instead of truncating it`() {
        composeRule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Column(Modifier.requiredWidth(300.dp)) {
                        PlaylistModernPlaybackActions(
                            songCount = 3,
                            shuffleEnabled = false,
                            repeatMode = Player.REPEAT_MODE_OFF,
                            onPlayInOrder = {},
                            onShufflePlay = {},
                            onToggleShuffle = {},
                            onCycleRepeatMode = {},
                            onExportToLocalPlaylist = {},
                            onEnterSelection = {}
                        )
                    }
                }
            }
        }

        val pill = bounds(CoreCommonR.string.player_play_all)
        val shuffle = bounds(CoreCommonR.string.playlist_mode_order)
        val more = bounds(CoreCommonR.string.playlist_playback_more_options)
        assertTrue("pill should sit above the buttons: $pill / $shuffle", pill.bottom <= shuffle.top)
        assertTrue("trailing buttons stay on the right", more.left > shuffle.right)
        val layouts = mutableListOf<TextLayoutResult>()
        composeRule.onNodeWithText(context.getString(CoreCommonR.string.player_play_all), useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse("play label must stay readable", layouts.single().isLineEllipsized(0))
    }

    private fun bounds(label: Int) =
        composeRule.onNodeWithContentDescription(context.getString(label)).fetchSemanticsNode().boundsInRoot
}
