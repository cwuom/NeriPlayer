package moe.ouom.neriplayer.ui.screen.download

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.ouom.neriplayer.data.model.download.DownloadedSong
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class DownloadPageContentWidthTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `tablet download manager centers its list and stats at the content max width`() {
        setDownloadManagerContent()

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val listBounds = composeRule.onNodeWithTag(DOWNLOAD_MANAGER_LIST_TAG).getBoundsInRoot()
        val statsBounds = composeRule.onNodeWithTag(DOWNLOAD_MANAGER_STATS_TAG).getBoundsInRoot()
        val rootCenter = (rootBounds.left + rootBounds.right) / 2

        assertDpEquals(DownloadPageContentMaxWidth, listBounds.right - listBounds.left)
        assertDpEquals(rootCenter, (listBounds.left + listBounds.right) / 2)
        assertDpEquals(DownloadPageContentMaxWidth - 32.dp, statsBounds.right - statsBounds.left)
        assertDpEquals(listBounds.left + 16.dp, statsBounds.left)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun `phone download manager list keeps the full window width`() {
        setDownloadManagerContent()

        val rootBounds = composeRule.onRoot().getBoundsInRoot()
        val listBounds = composeRule.onNodeWithTag(DOWNLOAD_MANAGER_LIST_TAG).getBoundsInRoot()

        assertDpEquals(rootBounds.left, listBounds.left)
        assertDpEquals(rootBounds.right, listBounds.right)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun `content width modifier caps wide parents and fills narrow ones`() {
        composeRule.setContent {
            Box(Modifier.width(1200.dp).height(40.dp)) {
                Box(Modifier.downloadPageContentWidth().height(10.dp).testTag("wide"))
            }
            Box(Modifier.width(300.dp).height(40.dp)) {
                Box(Modifier.downloadPageContentWidth().height(10.dp).testTag("narrow"))
            }
        }

        val wide = composeRule.onNodeWithTag("wide").getBoundsInRoot()
        val narrow = composeRule.onNodeWithTag("narrow").getBoundsInRoot()
        assertDpEquals(140.dp, wide.left)
        assertDpEquals(1060.dp, wide.right)
        assertDpEquals(0.dp, narrow.left)
        assertDpEquals(300.dp, narrow.right)
    }

    private fun setDownloadManagerContent() {
        val songs = (1L..3L).map { id ->
            DownloadedSong(
                id = id,
                name = "Song $id",
                artist = "Artist $id",
                album = "Album",
                filePath = "/music/$id.flac",
                fileSize = 1_000L,
                downloadTime = 1_700_000_000_000L
            )
        }
        composeRule.setContent {
            DownloadManagerContent(
                downloadedSongs = songs,
                legacyPreviewClips = emptyMap(),
                isRefreshing = false,
                deleteProgress = null,
                deleteFailureDismissed = false,
                listState = LazyListState(),
                offlineMode = true,
                onBack = {},
                onOpenDownloadProgress = {},
                onRefresh = {},
                onDismissDeleteFailure = {},
                onDeleteSongs = { _, _, _ -> },
                onPlaySong = {}
            )
        }
    }

    private fun assertDpEquals(expected: Dp, actual: Dp) {
        assertEquals(expected.value, actual.value, 0.5f)
    }
}
