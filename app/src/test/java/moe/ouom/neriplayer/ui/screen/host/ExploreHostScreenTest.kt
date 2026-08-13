package moe.ouom.neriplayer.ui.screen.host

import moe.ouom.neriplayer.core.api.youtube.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreHostScreenTest {

    @Test
    fun `creator detail state key is scoped to creator browse id`() {
        val creator = YouTubeMusicCreatorSummary(
            browseId = "UCdemoCreator",
            title = "Demo Creator",
            subtitle = "Artist",
            coverUrl = ""
        )

        assertEquals(
            "youtube_music_creator_detail_UCdemoCreator",
            youtubeMusicCreatorDetailStateKey(creator)
        )
    }

    @Test
    fun `creator album back returns to the creator page`() {
        val creator = YouTubeMusicCreatorSummary(
            browseId = "UCdemoCreator",
            title = "Demo Creator",
            subtitle = "Artist",
            coverUrl = ""
        )
        val album = YouTubeMusicPlaylist(
            browseId = "MPREalbum",
            playlistId = "MPREalbum",
            title = "Demo Album",
            subtitle = "2026",
            coverUrl = "",
            creatorName = creator.title
        )

        val backTarget = resolveExploreSelectedDetailBackTarget(
            ExploreSelectedItem.YouTubeMusic(
                playlist = album,
                parentCreator = creator
            )
        )

        assertEquals(ExploreSelectedItem.YouTubeMusicCreator(creator), backTarget)
    }
}
