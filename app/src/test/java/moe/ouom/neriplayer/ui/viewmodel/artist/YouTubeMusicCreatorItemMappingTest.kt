package moe.ouom.neriplayer.ui.viewmodel.artist

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItem
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorItemType
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSection
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import moe.ouom.neriplayer.ui.viewmodel.tab.YouTubeMusicPlaylist
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicCreatorItemMappingTest {

    @Test
    fun `song mapping requires a video id`() {
        assertNull(item(videoId = "  ").toCreatorSongItem())
    }

    @Test
    fun `song mapping keeps explicit artist album and cover`() {
        val song = item(
            videoId = "vid1",
            artist = "Artist",
            album = "Album",
            coverUrl = "https://cover.example/vid1.jpg"
        ).toCreatorSongItem()!!

        assertEquals(stableYouTubeMusicId("vid1"), song.id)
        assertEquals("Title", song.name)
        assertEquals("Artist", song.artist)
        assertEquals("Album", song.album)
        assertEquals(stableYouTubeMusicId("vid1|Album"), song.albumId)
        assertEquals(215_000L, song.durationMs)
        assertEquals("https://cover.example/vid1.jpg", song.coverUrl)
        assertEquals(buildYouTubeMusicMediaUri("vid1"), song.mediaUri)
        assertEquals("youtubeMusic", song.channelId)
        assertEquals("vid1", song.audioId)
    }

    @Test
    fun `song mapping falls back to subtitle default labels and thumbnail`() {
        val fromSubtitle = item(videoId = "vid2", subtitle = "Subtitle Artist").toCreatorSongItem()!!

        assertEquals("Subtitle Artist", fromSubtitle.artist)
        assertEquals("YouTube Music", fromSubtitle.album)
        assertEquals("https://i.ytimg.com/vi/vid2/hqdefault.jpg", fromSubtitle.coverUrl)
        assertEquals(fromSubtitle.coverUrl, fromSubtitle.originalCoverUrl)

        val anonymous = item(videoId = "vid3").toCreatorSongItem()!!

        assertEquals("YouTube", anonymous.artist)
        assertEquals("YouTube", anonymous.originalArtist)
        assertEquals(stableYouTubeMusicId("vid3|YouTube Music"), anonymous.albumId)
    }

    @Test
    fun `playlist mapping derives the playlist id from VL browse ids`() {
        assertNull(item(browseId = " ").toCreatorPlaylist())
        assertEquals(
            YouTubeMusicPlaylist(
                browseId = "VLPLabc",
                playlistId = "PLabc",
                title = "Title",
                subtitle = "Subtitle",
                coverUrl = "cover",
                trackCount = 0
            ),
            item(browseId = "VLPLabc", subtitle = "Subtitle", coverUrl = "cover").toCreatorPlaylist()
        )
        assertEquals(
            "OLAK5uy_album",
            item(browseId = "MPREb_album", playlistId = "OLAK5uy_album").toCreatorPlaylist()?.playlistId
        )
    }

    @Test
    fun `creator summary exposes channel ids only for UC browse ids`() {
        assertNull(item(browseId = "").toCreatorSummary())

        val channel = item(browseId = "UCartist", subtitle = "1M subscribers").toCreatorSummary()!!
        assertEquals("UCartist", channel.browseId)
        assertEquals("UCartist", channel.channelId)
        assertEquals("1M subscribers", channel.subtitle)

        assertEquals("", item(browseId = "MPLAartist").toCreatorSummary()?.channelId)
    }

    @Test
    fun `section key combines the more endpoint with the trimmed title`() {
        assertEquals(
            "||Top songs",
            youtubeMusicCreatorSectionKey(
                YouTubeMusicCreatorSection(title = " Top songs ", items = emptyList())
            )
        )
        assertEquals(
            "VLPLsongs|ggMx|Songs",
            youtubeMusicCreatorSectionKey(
                YouTubeMusicCreatorSection(
                    title = "Songs",
                    items = emptyList(),
                    moreEndpoint = YouTubeMusicCreatorBrowseEndpoint("VLPLsongs", "ggMx")
                )
            )
        )
    }

    private fun item(
        videoId: String = "",
        browseId: String = "",
        playlistId: String = "",
        subtitle: String = "",
        artist: String = "",
        album: String = "",
        coverUrl: String = ""
    ) = YouTubeMusicCreatorItem(
        type = YouTubeMusicCreatorItemType.Song,
        title = "Title",
        subtitle = subtitle,
        coverUrl = coverUrl,
        videoId = videoId,
        browseId = browseId,
        playlistId = playlistId,
        artist = artist,
        album = album,
        durationMs = 215_000L
    )
}
