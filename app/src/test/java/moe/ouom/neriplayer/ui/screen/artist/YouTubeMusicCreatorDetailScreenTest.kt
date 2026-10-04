package moe.ouom.neriplayer.ui.screen.artist

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicCreatorDetailScreenTest {

    @Test
    fun creatorSplitLayout_requiresTabletAndEnoughActualPaneWidth() {
        assertFalse(useCreatorDetailSplitLayout(tabletDevice = false, availableWidthDp = 1280f))
        assertFalse(useCreatorDetailSplitLayout(tabletDevice = true, availableWidthDp = 599f))
        assertTrue(useCreatorDetailSplitLayout(tabletDevice = true, availableWidthDp = 600f))
        assertTrue(useCreatorDetailSplitLayout(tabletDevice = true, availableWidthDp = 800f))
    }

    @Test
    fun creatorDetailStateKeys_areScopedToCreatorAndSection() {
        val section = YouTubeMusicCreatorSection(
            title = "Albums",
            items = emptyList(),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint(
                browseId = "UCdemoCreator",
                params = "albums"
            )
        )

        assertEquals(
            "UCdemoCreator|UCdemoCreator|albums|Albums|0",
            youtubeMusicCreatorSectionScrollStateKey(
                creatorBrowseId = "UCdemoCreator",
                section = section,
                sectionIndex = 0
            )
        )
        assertNotEquals(
            youtubeMusicCreatorSectionScrollStateKey(
                creatorBrowseId = "UCdemoCreator",
                section = section,
                sectionIndex = 0
            ),
            youtubeMusicCreatorSectionScrollStateKey(
                creatorBrowseId = "UCdemoCreator",
                section = section,
                sectionIndex = 1
            )
        )
    }

    @Test
    fun creatorDetailViewModelKeys_areStableAndIsolatedByCreator() {
        assertEquals(
            youtubeMusicCreatorDetailViewModelKey("UCdemoCreator"),
            youtubeMusicCreatorDetailViewModelKey("UCdemoCreator")
        )
        assertTrue(
            youtubeMusicCreatorDetailViewModelKey("UCparent") !=
                youtubeMusicCreatorDetailViewModelKey("UCchild")
        )
    }

    @Test
    fun sectionMore_isShownForPlayableTopSongs() {
        val section = YouTubeMusicCreatorSection(
            title = "TOP SONGS",
            items = listOf(
                YouTubeMusicCreatorItem(
                    type = YouTubeMusicCreatorItemType.Song,
                    title = "Top Song",
                    subtitle = "Creator",
                    coverUrl = "",
                    videoId = "top-song"
                )
            ),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint(
                browseId = "UCdemoCreator",
                params = "wAEB8gECAg%3D%3D"
            )
        )

        assertTrue(shouldShowYouTubeMusicCreatorSectionMore(section))
    }

    @Test
    fun sectionMore_isHiddenForNonPlayableSections() {
        val section = YouTubeMusicCreatorSection(
            title = "Albums",
            items = listOf(
                YouTubeMusicCreatorItem(
                    type = YouTubeMusicCreatorItemType.Album,
                    title = "Album",
                    subtitle = "2026",
                    coverUrl = "",
                    browseId = "MPREalbum"
                )
            ),
            moreEndpoint = YouTubeMusicCreatorBrowseEndpoint(
                browseId = "UCdemoCreator",
                params = "wAEB8gECAw%3D%3D"
            )
        )

        assertFalse(shouldShowYouTubeMusicCreatorSectionMore(section))
    }

    @Test
    fun creatorAlbum_keepsParentCreatorName() {
        val playlist = preserveYouTubeMusicCreatorName(
            playlist = YouTubeMusicPlaylist(
                browseId = "MPREalbum",
                playlistId = "MPREalbum",
                title = "Album",
                subtitle = "2026",
                coverUrl = ""
            ),
            creatorName = "Demo Creator"
        )

        assertEquals("Demo Creator", playlist.creatorName)
    }

    @Test
    fun creatorItemsTitle_keepsCreatorContext() {
        assertEquals(
            "Demo Creator · TOP SONGS",
            resolveYouTubeMusicCreatorItemsTitle(
                creatorName = "Demo Creator",
                sectionTitle = "TOP SONGS",
                loadedTitle = ""
            )
        )
    }
}
