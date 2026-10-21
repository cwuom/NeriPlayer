package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 九模块扩展解析器的单测。
 *
 * 用例里的 JSON 都是实测接口返回的结构子集，重点守住「接口没给就留空」这条底线。
 */
class NeteaseEncyclopediaExtendedParserTest {

    @Test
    fun `song detail parses album company publish time duration and artist`() {
        val detail = parseNeteaseSongDetail(SONG_DETAIL_JSON)

        requireNotNull(detail)
        assertEquals("起风了", detail.name)
        assertEquals("买辣椒也用券", detail.artist)
        assertEquals(12138269L, detail.artistId)
        assertEquals(7255L, detail.albumId)
        assertEquals("起风了", detail.albumName)
        assertEquals("http://p1.music.126.net/cover.jpg", detail.albumPicUrl)
        assertEquals("哇唧唧哇", detail.albumCompany)
        assertEquals(1530547200000L, detail.publishTimeMs)
        assertEquals(325000L, detail.durationMs)
    }

    @Test
    fun `song detail joins multiple artists with slash`() {
        val raw = """
            {"code":200,"songs":[{"name":"合唱曲","artists":[
              {"id":1,"name":"甲"},{"id":2,"name":"  "},{"id":3,"name":"乙"}
            ]}]}
        """.trimIndent()

        val detail = parseNeteaseSongDetail(raw)

        requireNotNull(detail)
        assertEquals("甲/乙", detail.artist)
        assertEquals(1L, detail.artistId)
        assertNull(detail.albumId)
        assertNull(detail.albumName)
        assertNull(detail.albumCompany)
        assertNull(detail.publishTimeMs)
        assertNull(detail.durationMs)
    }

    @Test
    fun `song detail missing album keeps every album field null`() {
        val raw = """
            {"code":200,"songs":[{"name":"单曲","artists":[{"id":9,"name":"某人"}]}]}
        """.trimIndent()

        val detail = parseNeteaseSongDetail(raw)

        requireNotNull(detail)
        assertNull(detail.albumId)
        assertNull(detail.albumPicUrl)
        assertNull(detail.albumCompany)
        assertEquals(9L, detail.artistId)
    }

    @Test
    fun `song detail returns null for non success code and empty songs`() {
        assertNull(parseNeteaseSongDetail("""{"code":404,"songs":[]}"""))
        assertNull(parseNeteaseSongDetail("""{"code":200,"songs":[]}"""))
        assertNull(parseNeteaseSongDetail("""{"code":200}"""))
    }

    @Test
    fun `song detail invalid json throws so caller treats it as retryable`() {
        val failure = runCatching { parseNeteaseSongDetail("not json") }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun `song detail treats zero and negative numbers as missing`() {
        val raw = """
            {"code":200,"songs":[{"name":"零值","artists":[{"id":0,"name":"无名"}],
              "album":{"id":0,"name":"","company":"  ","picUrl":""},
              "publishTime":0,"duration":-1}]}
        """.trimIndent()

        val detail = parseNeteaseSongDetail(raw)

        requireNotNull(detail)
        assertNull(detail.albumId)
        assertNull(detail.albumName)
        assertNull(detail.albumCompany)
        assertNull(detail.albumPicUrl)
        assertNull(detail.publishTimeMs)
        assertNull(detail.durationMs)
        // 主歌手 id 为 0 表示无有效艺人，此时不再发起艺人请求
        assertNull(detail.artistId)
    }

    @Test
    fun `artist info parses name pic desc and sizes`() {
        val artist = parseNeteaseArtistInfo(ARTIST_JSON)

        requireNotNull(artist)
        assertEquals(12138269L, artist.artistId)
        assertEquals("买辣椒也用券", artist.name)
        assertEquals("https://p1.music.126.net/avatar.jpg", artist.picUrl)
        assertEquals("唱作音乐人。", artist.briefDesc)
        assertEquals(10, artist.albumSize)
        assertEquals(50, artist.musicSize)
    }

    @Test
    fun `artist info fans count is always null even if the source claims one`() {
        val withFans = """
            {"code":200,"artist":{"id":7,"name":"某人","fansCount":999999,
              "albumSize":1,"musicSize":2}}
        """.trimIndent()

        val artist = parseNeteaseArtistInfo(withFans)

        requireNotNull(artist)
        assertNull(artist.fansCount)
        assertNull(parseNeteaseArtistInfo(ARTIST_JSON)?.fansCount)
    }

    @Test
    fun `artist info returns null for non success code missing artist or blank name`() {
        assertNull(parseNeteaseArtistInfo("""{"code":404}"""))
        assertNull(parseNeteaseArtistInfo("""{"code":200}"""))
        assertNull(parseNeteaseArtistInfo("""{"code":200,"artist":{"name":"没id"}}"""))
        assertNull(parseNeteaseArtistInfo("""{"code":200,"artist":{"id":1,"name":"  "}}"""))
    }

    @Test
    fun `similar songs parse songs and rewrite cover scheme to https`() {
        val songs = parseNeteaseSimilarSongs(SIMILAR_SONG_JSON)

        assertEquals(
            listOf(
                EncyclopediaSimilarSong(
                    songId = 186016L,
                    name = "晴天",
                    artist = "周杰伦",
                    coverUrl = "https://p1.music.126.net/cover1.jpg"
                ),
                EncyclopediaSimilarSong(
                    songId = 186017L,
                    name = "七里香",
                    artist = "周杰伦",
                    coverUrl = "https://p1.music.126.net/cover2.jpg"
                )
            ),
            songs
        )
        assertFalse(songs.any { it.coverUrl?.startsWith("http://") == true })
    }

    @Test
    fun `similar songs skip invalid entries and missing ids or titles`() {
        val raw = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_SIMILAR_SONG","creatives":[
                {"resources":[
                  {"resourceType":"SONG","valid":false,"resourceId":1,
                   "uiElement":{"mainTitle":{"title":"失效"},"subTitles":[{"title":"某人"}]}},
                  {"resourceType":"SONG","valid":true,
                   "uiElement":{"mainTitle":{"title":"无id"},"subTitles":[{"title":"某人"}]}},
                  {"resourceType":"SONG","valid":true,"resourceId":3,
                   "uiElement":{"subTitles":[{"title":"某人"}]}},
                  {"resourceType":"SONG","valid":true,"resourceId":4,
                   "uiElement":{"mainTitle":{"title":"无歌手"}}},
                  {"resourceType":"PLAYLIST","valid":true,"resourceId":5,
                   "uiElement":{"mainTitle":{"title":"歌单不是歌"},"subTitles":[{"title":"某人"}]}},
                  {"resourceType":"SONG","valid":true,"resourceId":6,
                   "uiElement":{"mainTitle":{"title":"无封面"},"subTitles":[{"title":"某人"}]}}
                ]}
              ]}
            ]}}
        """.trimIndent()

        val songs = parseNeteaseSimilarSongs(raw)

        assertEquals(1, songs.size)
        assertEquals(6L, songs[0].songId)
        assertEquals("无封面", songs[0].name)
        assertNull(songs[0].coverUrl)
    }

    @Test
    fun `similar songs return empty list without the block or on bad payload`() {
        val noBlock = """
            {"code":200,"data":{"blocks":[{"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST"}]}}
        """.trimIndent()

        assertTrue(parseNeteaseSimilarSongs(noBlock).isEmpty())
        assertTrue(parseNeteaseSimilarSongs("not json").isEmpty())
        assertTrue(parseNeteaseSimilarSongs("""{"code":400}""").isEmpty())
        assertTrue(parseNeteaseSimilarSongs("""{"code":200,"data":{}}""").isEmpty())
    }

    @Test
    fun `related playlists parse play count and cover`() {
        val playlists = parseNeteaseRelatedPlaylists(RELATED_PLAYLIST_JSON)

        assertEquals(
            listOf(
                EncyclopediaRelatedPlaylist(
                    playlistId = 456L,
                    name = "华语流行精选",
                    coverUrl = "https://p1.music.126.net/list.jpg",
                    playCount = 6813346L,
                    trackCount = null
                )
            ),
            playlists
        )
        assertNull(playlists[0].trackCount)
    }

    @Test
    fun `related playlists keep play count null when absent and always null track count`() {
        val raw = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST","creatives":[
                {"resources":[
                  {"resourceType":"PLAYLIST","resourceId":789,
                   "uiElement":{"mainTitle":{"title":"无播放量"},"images":[{"imageUrl":"https://x.jpg"}]}}
                ]}
              ]}
            ]}}
        """.trimIndent()

        val playlists = parseNeteaseRelatedPlaylists(raw)

        assertEquals(1, playlists.size)
        assertEquals(789L, playlists[0].playlistId)
        assertNull(playlists[0].playCount)
        assertNull(playlists[0].trackCount)
    }

    @Test
    fun `related playlists return empty list without the block or on bad payload`() {
        val noBlock = """
            {"code":200,"data":{"blocks":[{"code":"SONG_PLAY_ABOUT_SONG_BASIC"}]}}
        """.trimIndent()

        assertTrue(parseNeteaseRelatedPlaylists(noBlock).isEmpty())
        assertTrue(parseNeteaseRelatedPlaylists("").isEmpty())
        assertTrue(parseNeteaseRelatedPlaylists("""{"code":500}""").isEmpty())
        assertTrue(parseNeteaseRelatedPlaylists("""{"code":200,"data":{"blocks":[]}}""").isEmpty())
    }

    @Test
    fun `summary similar songs and playlists all come from one raw response`() {
        val raw = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_SONG_BASIC","creatives":[
                {"creativeType":"songTag","resources":[{"uiElement":{"mainTitle":{"title":"流行"}}}]}
              ]},
              {"code":"SONG_PLAY_ABOUT_SIMILAR_SONG","creatives":[
                {"resources":[{"resourceType":"SONG","valid":true,"resourceId":11,
                  "uiElement":{"mainTitle":{"title":"像风一样"},
                    "subTitles":[{"title":"买辣椒也用券"}]}}]}
              ]},
              {"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST","creatives":[
                {"resources":[{"resourceType":"PLAYLIST","resourceId":22,
                  "resourceExt":{"playCount":42},
                  "uiElement":{"mainTitle":{"title":"治愈歌单"}}}]}
              ]}
            ]}}
        """.trimIndent()

        val summary = parseNeteaseEncyclopediaSummary(raw)
        val songs = parseNeteaseSimilarSongs(raw)
        val playlists = parseNeteaseRelatedPlaylists(raw)

        requireNotNull(summary)
        assertEquals(listOf("流行"), summary.genres)
        assertEquals(1, songs.size)
        assertEquals("像风一样", songs[0].name)
        assertEquals(1, playlists.size)
        assertEquals("治愈歌单", playlists[0].name)
        assertEquals(42L, playlists[0].playCount)
    }

    private companion object {
        val SONG_DETAIL_JSON = """
            {"code":200,"songs":[{
              "id":186016,
              "name":"起风了",
              "duration":325000,
              "publishTime":1530547200000,
              "artists":[{"id":12138269,"name":"买辣椒也用券"}],
              "album":{"id":7255,"name":"起风了",
                "picUrl":"http://p1.music.126.net/cover.jpg","company":"哇唧唧哇"}
            }]}
        """.trimIndent()

        val ARTIST_JSON = """
            {"code":200,"artist":{
              "id":12138269,"name":"买辣椒也用券",
              "picUrl":"https://p1.music.126.net/avatar.jpg",
              "briefDesc":"唱作音乐人。","albumSize":10,"musicSize":50
            }}
        """.trimIndent()

        val SIMILAR_SONG_JSON = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_SIMILAR_SONG","creatives":[
                {"resources":[
                  {"resourceType":"SONG","valid":true,"resourceId":186016,
                   "uiElement":{"mainTitle":{"title":"晴天"},
                     "subTitles":[{"title":"周杰伦"}],
                     "images":[{"imageUrl":"http://p1.music.126.net/cover1.jpg"}]}},
                  {"resourceType":"SONG","valid":true,"resourceId":186017,
                   "uiElement":{"mainTitle":{"title":"七里香"},
                     "subTitles":[{"title":"周杰伦"}],
                     "images":[{"imageUrl":"http://p1.music.126.net/cover2.jpg"}]}}
                ]}
              ]}
            ]}}
        """.trimIndent()

        val RELATED_PLAYLIST_JSON = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST","creatives":[
                {"resources":[
                  {"resourceType":"PLAYLIST","resourceId":456,
                   "resourceExt":{"playCount":6813346},
                   "uiElement":{"mainTitle":{"title":"华语流行精选"},
                     "images":[{"imageUrl":"http://p1.music.126.net/list.jpg"}]}}
                ]}
              ]}
            ]}}
        """.trimIndent()
    }
}
