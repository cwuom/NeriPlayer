package moe.ouom.neriplayer.ui.screen.tab.explore

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class ExploreTabletContentWidthTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet search results share the centered search chrome width`() {
        renderSearchResults(isTabletLayout = true)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val row = composeRule.onNodeWithTag(ROW_TAG).getUnclippedBoundsInRoot()
        val container = min(root.width, ExploreSearchContentMaxWidth)
        val expectedLeft = (root.width - container) / 2 + 28.dp

        assertDpEquals(expectedLeft, row.left)
        assertDpEquals(container - 56.dp, row.width)
        assertDpEquals(root.width - expectedLeft, row.right)
    }

    @Test
    fun `phone search results keep edge to edge rows`() {
        renderSearchResults(isTabletLayout = false)

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val row = composeRule.onNodeWithTag(ROW_TAG).getUnclippedBoundsInRoot()

        assertDpEquals(0.dp, row.left)
        assertDpEquals(root.width, row.width)
    }

    @Test
    @Config(qualifiers = "w1600dp-h1000dp-land")
    fun `tablet youtube browse grid is centered within the home content width`() {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().testTag(ROOT_TAG)) {
                YouTubeMusicExploreContent(
                    browse = YouTubeBrowseState(
                        playlists = listOf(playlist("first")),
                        loading = false,
                        error = null
                    ),
                    onRetry = {},
                    onClick = {},
                    offlineMode = true,
                    gridState = rememberLazyGridState(),
                    isTabletLayout = true
                )
            }
        }

        val root = composeRule.onNodeWithTag(ROOT_TAG).getUnclippedBoundsInRoot()
        val cover = composeRule.onNodeWithContentDescription("first").getUnclippedBoundsInRoot()
        val container = min(root.width, ExploreBrowseContentMaxWidth)

        assertDpEquals(max((root.width - container) / 2, 0.dp) + 56.dp, cover.left)
    }

    private fun renderSearchResults(isTabletLayout: Boolean) {
        composeRule.setContent {
            Box(Modifier.fillMaxSize().testTag(ROOT_TAG)) {
                ExploreSearchResultsList(
                    state = rememberLazyListState(),
                    isTabletLayout = isTabletLayout,
                    miniPlayerHeight = 0.dp
                ) {
                    item {
                        Text("row", Modifier.fillMaxWidth().height(48.dp).testTag(ROW_TAG))
                    }
                }
            }
        }
    }

    private fun playlist(title: String) = YouTubeMusicPlaylist(
        browseId = "VL$title",
        playlistId = title,
        title = title,
        subtitle = "",
        coverUrl = "",
        trackCount = 1
    )

    private fun assertDpEquals(expected: Dp, actual: Dp) {
        assertEquals(expected.value, actual.value, 0.5f)
    }

    private companion object {
        const val ROOT_TAG = "root"
        const val ROW_TAG = "row"
    }
}
