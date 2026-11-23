package moe.ouom.neriplayer.ui.screen.playlist

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.settings.appearance.UiStyle
import moe.ouom.neriplayer.ui.theme.NeriTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w1280dp-h800dp-land")
class PlaylistTwoPaneCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `material pane card ends at the bottom of its actions`() = checkCard(UiStyle.Material)

    @Test
    fun `clean pane card ends at the bottom of its actions`() = checkCard(UiStyle.Clean)

    private fun checkCard(style: UiStyle) {
        composeRule.setContent {
            NeriTheme(
                followSystemDark = false,
                forceDark = false,
                dynamicColor = false,
                seedColorHex = "885522",
                uiStyle = style,
                animateColors = false
            ) {
                Box(Modifier.fillMaxSize().testTag("host")) {
                    PlaylistTwoPaneBody(
                        twoPane = true,
                        modifier = Modifier.fillMaxSize(),
                        pane = {
                            PlaylistModernHeroHeader(
                                displayName = "Mix",
                                coverUrl = null,
                                subtitle = "3 songs",
                                offlineMode = true,
                                height = 0.dp
                            )
                            PlaylistModernActionSheet(
                                coverUrl = null,
                                offlineMode = true,
                                modifier = Modifier.testTag("actions")
                            ) {
                                PlaylistModernPlaybackActions(
                                    songCount = 3,
                                    shuffleEnabled = false,
                                    repeatMode = 0,
                                    onPlayInOrder = {},
                                    onShufflePlay = {},
                                    onToggleShuffle = {},
                                    onCycleRepeatMode = {},
                                    onExportToLocalPlaylist = {}
                                )
                            }
                        },
                        list = {}
                    )
                }
            }
        }
        val host = composeRule.onNodeWithTag("host").getUnclippedBoundsInRoot()
        val card = composeRule.onNodeWithTag(PlaylistPaneCardTag).getUnclippedBoundsInRoot()
        val actions = composeRule.onNodeWithTag("actions").getUnclippedBoundsInRoot()
        // 卡片按内容收口, 圆角才会落在操作区下沿; 撑满侧栏时底边会被方切
        assertEquals(actions.bottom.value, card.bottom.value, 0.5f)
        assertTrue("card must not stretch to the pane bottom", card.bottom < host.bottom - 40.dp)
        // 封面上方留白和左右一致
        val cover = composeRule.onNodeWithTag(PlaylistHeroCoverTag).getUnclippedBoundsInRoot()
        assertEquals((cover.left - card.left).value, (cover.top - card.top).value, 1f)
    }
}
