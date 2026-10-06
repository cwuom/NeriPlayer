package moe.ouom.neriplayer.ui.screen.tab.home

import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicHomeItem
import moe.ouom.neriplayer.platform.youtube.api.transport.buildYouTubeMusicMediaUri
import moe.ouom.neriplayer.platform.youtube.api.transport.stableYouTubeMusicId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeMusicHomeSongMappingTest {

    @Test
    fun `home items without a video are not playable`() {
        val item = YouTubeMusicHomeItem(
            title = "Mix",
            subtitle = "Playlist",
            coverUrl = "https://example.com/mix.jpg",
            browseId = "VLPLmix",
            videoId = "  "
        )

        assertNull(item.toPlayableSongItem(sectionTitle = "Quick picks"))
    }

    @Test
    fun `playlist backed item keeps its playlist context in identity and media uri`() {
        val song = YouTubeMusicHomeItem(
            title = "Song",
            subtitle = "Song • Artist • Album",
            coverUrl = "https://example.com/song.jpg",
            browseId = "VLPLqueue",
            videoId = "video-1"
        ).toPlayableSongItem(sectionTitle = "Quick picks")!!

        assertEquals(stableYouTubeMusicId("video-1"), song.id)
        assertEquals(stableYouTubeMusicId("PLqueue"), song.albumId)
        assertEquals(buildYouTubeMusicMediaUri("video-1", "PLqueue"), song.mediaUri)
        assertEquals("https://example.com/song.jpg", song.coverUrl)
        assertEquals("https://example.com/song.jpg", song.originalCoverUrl)
        assertEquals("Song", song.originalName)
        assertEquals(song.artist, song.originalArtist)
    }

    @Test
    fun `item without playlist context is grouped by its section`() {
        val song = YouTubeMusicHomeItem(
            title = "Song",
            subtitle = "Song • Artist",
            coverUrl = "",
            videoId = "video-2"
        ).toPlayableSongItem(sectionTitle = "Quick picks")!!

        assertEquals(stableYouTubeMusicId("Quick picks"), song.albumId)
        assertEquals(buildYouTubeMusicMediaUri("video-2", null), song.mediaUri)
        assertNull(song.coverUrl)
        assertNull(song.originalCoverUrl)
    }

    @Test
    fun `item without playlist or section context is grouped by its own video`() {
        val song = YouTubeMusicHomeItem(
            title = "Song",
            subtitle = "",
            coverUrl = "",
            browseId = "VL",
            videoId = "video-3"
        ).toPlayableSongItem(sectionTitle = "")!!

        assertEquals(stableYouTubeMusicId("video-3"), song.albumId)
        assertEquals(buildYouTubeMusicMediaUri("video-3", null), song.mediaUri)
    }
}
