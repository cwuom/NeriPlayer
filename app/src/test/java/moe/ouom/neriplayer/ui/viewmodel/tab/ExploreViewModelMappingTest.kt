package moe.ouom.neriplayer.ui.viewmodel.tab

import android.app.Application
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.bilibili.video.UgcSeason
import moe.ouom.neriplayer.data.model.bilibili.video.VideoBasicInfo
import moe.ouom.neriplayer.data.model.bilibili.video.VideoStats
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResult
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicSearchResultType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class ExploreViewModelMappingTest {

    @Test
    fun `high quality playlists map every entry and upgrade cover urls`() {
        val playlists = parseExploreHighQualityPlaylists(
            """
            {"code":200,"playlists":[
              {"id":7,"name":"Night","coverImgUrl":"http://p1.music.126.net/a.jpg","playCount":42,"trackCount":12},
              "not an object",
              {"id":8,"name":"Day"}
            ]}
            """.trimIndent()
        )

        assertEquals(listOf(7L, 8L), playlists.map { it.id })
        assertEquals("https://p1.music.126.net/a.jpg", playlists[0].picUrl)
        assertEquals(42L, playlists[0].playCount)
        assertEquals(12, playlists[0].trackCount)
        assertEquals("Day", playlists[1].name)
        assertEquals(0, playlists[1].trackCount)
    }

    @Test
    fun `high quality playlists are empty for an error code or a missing list`() {
        assertTrue(parseExploreHighQualityPlaylists("""{"code":301,"playlists":[{"id":1}]}""").isEmpty())
        assertTrue(parseExploreHighQualityPlaylists("""{"code":200}""").isEmpty())
    }

    @Test
    fun `linked artist reads the nested data payload`() {
        val result = parseLinkedNeteaseArtist(
            raw = """
                {"data":{"artist":{"id":11,"name":"Singer","cover":"http://img/cover.jpg",
                "musicSize":30,"albumSize":4}}}
            """.trimIndent(),
            fallbackId = 99L,
            fallbackName = "Artist 99"
        )

        assertEquals(11L, result.artist.id)
        assertEquals("Singer", result.artist.name)
        assertEquals("https://img/cover.jpg", result.picUrl)
        assertEquals(30, result.musicSize)
        assertEquals(4, result.albumSize)
    }

    @Test
    fun `linked artist falls back through picture fields and blank names`() {
        val avatarOnly = parseLinkedNeteaseArtist(
            raw = """{"artist":{"name":" ","picUrl":"","avatar":"http://img/avatar.jpg"}}""",
            fallbackId = 5L,
            fallbackName = "Artist 5"
        )
        val legacyPicture = parseLinkedNeteaseArtist(
            raw = """{"artist":{"id":6,"img1v1Url":"https://img/legacy.jpg"}}""",
            fallbackId = 6L,
            fallbackName = "Artist 6"
        )
        val noPicture = parseLinkedNeteaseArtist(
            raw = """{"artist":{"id":7,"name":"Quiet"}}""",
            fallbackId = 7L,
            fallbackName = "Artist 7"
        )

        assertEquals(5L, avatarOnly.artist.id)
        assertEquals("Artist 5", avatarOnly.artist.name)
        assertEquals("https://img/avatar.jpg", avatarOnly.picUrl)
        assertEquals("Artist 6", legacyPicture.artist.name)
        assertEquals("https://img/legacy.jpg", legacyPicture.picUrl)
        assertEquals("Quiet", noPicture.artist.name)
        assertNull(noPicture.picUrl)
    }

    @Test
    fun `linked artist without an artist object uses the fallback identity`() {
        val result = parseLinkedNeteaseArtist(
            raw = """{"data":{}}""",
            fallbackId = 3L,
            fallbackName = "Artist 3"
        )

        assertEquals(3L, result.artist.id)
        assertEquals("Artist 3", result.artist.name)
        assertNull(result.picUrl)
        assertEquals(0, result.musicSize)
        assertEquals(0, result.albumSize)
    }

    @Test
    fun `YouTube Music result keeps its own metadata`() {
        val song = youtubeResult(
            artist = "Creator",
            album = "Album",
            coverUrl = "https://img/cover.jpg"
        ).toSongItem(application())

        assertEquals("Title", song.name)
        assertEquals("Creator", song.artist)
        assertEquals("Album", song.album)
        assertEquals("https://img/cover.jpg", song.coverUrl)
        assertEquals("https://img/cover.jpg", song.originalCoverUrl)
        assertEquals(180_000L, song.durationMs)
        assertEquals("youtubeMusic", song.channelId)
        assertEquals("vid123", song.audioId)
        assertTrue(song.mediaUri.orEmpty().endsWith("/vid123"))
    }

    @Test
    fun `YouTube Music result fills blank artist album and cover from its type`() {
        val app = application()
        val song = youtubeResult(type = YouTubeMusicSearchResultType.Song).toSongItem(app)
        val video = youtubeResult(type = YouTubeMusicSearchResultType.Video).toSongItem(app)

        assertEquals("YouTube", song.artist)
        assertEquals("YouTube", song.originalArtist)
        assertEquals("Song label", song.album)
        assertEquals("Video label", video.album)
        assertEquals("https://i.ytimg.com/vi/vid123/hqdefault.jpg", song.coverUrl)
        assertEquals("https://i.ytimg.com/vi/vid123/hqdefault.jpg", song.originalCoverUrl)
        assertTrue(song.albumId != video.albumId)
    }

    @Test
    fun `collection share falls back to the uploader when the season owner is unknown`() {
        val target = videoInfo(
            ownerMid = 100L,
            ugcSeason = UgcSeason(id = 9L, mid = 0L, title = "Season")
        ).toExploreLinkCollectionTarget(collectionShare())

        assertEquals(ExploreLinkTarget.BiliCollection(ownerMid = 100L, seasonId = 9L), target)
    }

    @Test
    fun `collection share without any owner or season cannot open a collection`() {
        val noOwner = videoInfo(
            ownerMid = 0L,
            ugcSeason = UgcSeason(id = 9L, mid = 0L, title = "Season")
        ).toExploreLinkCollectionTarget(collectionShare())
        val noSeason = videoInfo(ownerMid = 100L, ugcSeason = null)
            .toExploreLinkCollectionTarget(collectionShare())

        assertNull(noOwner)
        assertNull(noSeason)
    }

    private fun collectionShare() = ExploreLinkTarget.BiliVideo(
        bvid = "BV1V4m2BMEWN",
        isCollectionShare = true
    )

    private fun application(): Application = mock(Application::class.java).also { app ->
        `when`(app.getString(CoreCommonR.string.youtube_search_type_song)).thenReturn("Song label")
        `when`(app.getString(CoreCommonR.string.youtube_search_type_video)).thenReturn("Video label")
    }

    private fun youtubeResult(
        artist: String = "",
        album: String = "",
        coverUrl: String = "",
        type: YouTubeMusicSearchResultType = YouTubeMusicSearchResultType.Song
    ) = YouTubeMusicSearchResult(
        videoId = "vid123",
        title = "Title",
        artist = artist,
        album = album,
        subtitle = "",
        coverUrl = coverUrl,
        durationText = "3:00",
        durationMs = 180_000L,
        type = type
    )

    private fun videoInfo(ownerMid: Long, ugcSeason: UgcSeason?) = VideoBasicInfo(
        aid = 1L,
        bvid = "BV1V4m2BMEWN",
        title = "Video",
        coverUrl = "",
        desc = "",
        durationSec = 30,
        ownerMid = ownerMid,
        ownerName = "Uploader",
        ownerFace = "",
        stats = VideoStats(
            view = 0L,
            danmaku = 0L,
            reply = 0L,
            favorite = 0L,
            coin = 0L,
            share = 0L,
            like = 0L
        ),
        pages = emptyList(),
        ugcSeason = ugcSeason
    )
}
