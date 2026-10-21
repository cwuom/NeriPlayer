package moe.ouom.neriplayer.ui.screen.nowplaying.actions

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class, qualifiers = "en-rUS")
class SongEncyclopediaRelatedPlaylistsSectionTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun `english playlist counts use the existing thousand and million units`() {
        render(10_000L, 3_000_000L)

        composeRule.onNodeWithText("10.0K plays").assertExists()
        composeRule.onNodeWithText("3.0M plays").assertExists()
        composeRule.onNodeWithText("万", substring = true).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `chinese playlist counts retain the existing fractional ten thousand units`() {
        render(99_000L)

        composeRule.onNodeWithText("9.9万 次播放").assertExists()
    }

    @Test
    fun `zero count remains visible while a missing count has no invented value`() {
        render(0L, null)

        composeRule.onNodeWithText("0 plays").assertExists()
        composeRule.onNodeWithText("Fixture playlist 1").assertExists()
        composeRule.onNodeWithText("null", substring = true).assertDoesNotExist()
    }

    private fun render(vararg counts: Long?) {
        composeRule.setContent {
            MaterialTheme {
                SongEncyclopediaRelatedPlaylistsSection(
                    playlists = counts.mapIndexed { index, count ->
                        EncyclopediaRelatedPlaylist(index.toLong(), "Fixture playlist $index", playCount = count)
                    },
                    onOpenPlaylist = {}
                )
            }
        }
    }
}
