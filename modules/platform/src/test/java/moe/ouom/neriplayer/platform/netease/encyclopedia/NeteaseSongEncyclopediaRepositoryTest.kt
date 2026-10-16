package moe.ouom.neriplayer.platform.netease.encyclopedia

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaCreditRole
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaRelatedPlaylist
import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaSimilarSong
import moe.ouom.neriplayer.data.model.encyclopedia.encyclopediaSongKey
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 仓库级单测。
 *
 * [moe.ouom.neriplayer.platform.netease.api.client.NeteaseClient] 是 final class 且两个百科接口都是
 * suspend 函数，mockito 无法直接替身，因此这里注入 [NeteaseEncyclopediaApi] 假实现。
 */
class NeteaseSongEncyclopediaRepositoryTest {

    // ---------- resolveNeteaseSongId ----------

    @Test
    fun `song id prefers audio id then falls back to id and matched song id`() {
        assertEquals(42L, resolveNeteaseSongId(song(id = 7L, audioId = "42")))

        // audioId 非法（0 / 负数 / 非数字）时回退到正数 id
        assertEquals(7L, resolveNeteaseSongId(song(id = 7L, audioId = "0")))
        assertEquals(7L, resolveNeteaseSongId(song(id = 7L, audioId = "-3")))
        assertEquals(7L, resolveNeteaseSongId(song(id = 7L, audioId = "not-a-number")))
        assertEquals(7L, resolveNeteaseSongId(song(id = 7L, audioId = "")))

        // id 也非法时才回退到 matchedSongId
        assertEquals(9L, resolveNeteaseSongId(song(id = -1L, audioId = null, matchedSongId = "9")))
        assertEquals(9L, resolveNeteaseSongId(song(id = 0L, audioId = "0", matchedSongId = "9")))
    }

    @Test
    fun `all unusable identities resolve to null`() {
        assertNull(resolveNeteaseSongId(song(id = 0L, audioId = null, matchedSongId = null)))
        assertNull(resolveNeteaseSongId(song(id = -5L, audioId = "-1", matchedSongId = "0")))
        assertNull(resolveNeteaseSongId(song(id = 0L, audioId = null, matchedSongId = "-8")))
    }

    @Test
    fun `source detection accepts channel id album prefix and media uri`() {
        assertEquals(1L, resolveNeteaseSongId(song(id = 1L, album = "别的专辑", channelId = "netease")))
        assertEquals(1L, resolveNeteaseSongId(song(id = 1L, album = "别的专辑", channelId = "Netease")))
        assertEquals(1L, resolveNeteaseSongId(song(id = 1L, album = "Netease|专辑")))
        assertEquals(
            1L,
            resolveNeteaseSongId(
                song(id = 1L, album = "别的专辑", mediaUri = "https://music.163.com/song/1")
            )
        )
        assertEquals(
            1L,
            resolveNeteaseSongId(
                song(id = 1L, album = "别的专辑", mediaUri = "https://MUSIC.163.COM/song/1")
            )
        )
    }

    @Test
    fun `non netease sources resolve to null`() {
        // B 站
        assertNull(resolveNeteaseSongId(song(id = 1L, album = "Bilibili|100|BV1", channelId = "bilibili")))
        // 本地文件
        assertNull(resolveNeteaseSongId(song(id = 1L, album = "local", channelId = "local")))
        // YouTube
        assertNull(resolveNeteaseSongId(song(id = 1L, album = "YouTube Music", channelId = "youtube")))
        // 没有任何来源标记
        assertNull(resolveNeteaseSongId(song(id = 1L, album = "未知专辑")))
        // 有 mediaUri，但不是网易云域名
        assertNull(
            resolveNeteaseSongId(
                song(id = 1L, album = "未知专辑", mediaUri = "https://example.com/song/1.mp3")
            )
        )
    }

    // ---------- getEncyclopedia: NotSupported ----------

    @Test
    fun `non netease songs are not supported and never hit the network`() = runBlocking {
        val api = FakeEncyclopediaApi()
        val repository = repository(api)

        val result = repository.getEncyclopedia(
            song(id = 1L, album = "Bilibili|100|BV1", channelId = "bilibili")
        )

        assertEquals(SongEncyclopediaResult.NotSupported, result)
        assertTrue("must not request the summary", api.summarySongIds.isEmpty())
        assertTrue("must not request the creators", api.creatorSongIds.isEmpty())
    }

    @Test
    fun `local youtube and untagged songs are not supported`() = runBlocking {
        val api = FakeEncyclopediaApi()
        val repository = repository(api)

        listOf(
            song(id = 1L, album = "local", channelId = "local"),
            song(id = 2L, album = "YouTube Music", channelId = "youtube"),
            song(id = 3L, album = "未知专辑")
        ).forEach { item ->
            assertEquals(SongEncyclopediaResult.NotSupported, repository.getEncyclopedia(item))
        }

        assertTrue(api.summarySongIds.isEmpty())
        assertTrue(api.creatorSongIds.isEmpty())
    }

    // ---------- getEncyclopedia: NotAvailable / Failed ----------

    @Test
    fun `missing basic block means not available and skips the creator request`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { ONLY_RELATED_PLAYLIST_JSON })
        val repository = repository(api)

        val result = repository.getEncyclopedia(song(id = 77L))

        assertEquals(SongEncyclopediaResult.NotAvailable, result)
        assertEquals(listOf(77L), api.summarySongIds)
        assertTrue("no encyclopedia content means no creator lookup", api.creatorSongIds.isEmpty())
    }

    @Test
    fun `summary request failure becomes a failed result with the original cause`() = runBlocking {
        val failure = IllegalStateException("network down")
        val api = FakeEncyclopediaApi(summary = { throw failure })
        val repository = repository(api)

        val result = repository.getEncyclopedia(song(id = 88L))

        assertTrue(result is SongEncyclopediaResult.Failed)
        assertEquals(failure, (result as SongEncyclopediaResult.Failed).cause)
        assertEquals(listOf(88L), api.summarySongIds)
        assertTrue(api.creatorSongIds.isEmpty())
    }

    @Test
    fun `invalid summary payload becomes a failed result instead of not available`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { "not json" })
        val repository = repository(api)

        assertTrue(repository.getEncyclopedia(song(id = 1L)) is SongEncyclopediaResult.Failed)
    }

    @Test
    fun `cancellation from the summary request propagates to the caller`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { throw CancellationException("切歌取消") })
        val repository = repository(api)

        val failure = runCatching { repository.getEncyclopedia(song(id = 1L)) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    // ---------- getEncyclopedia: cache ----------

    @Test
    fun `repeated calls for the same song request the network only once`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { SUMMARY_JSON }, creators = { CREATORS_JSON })
        val repository = repository(api)

        val first = repository.getEncyclopedia(song(id = 5L))
        val second = repository.getEncyclopedia(song(id = 5L))

        assertEquals(listOf(5L), api.summarySongIds)
        assertEquals(listOf(5L), api.creatorSongIds)
        assertTrue(first is SongEncyclopediaResult.Success)
        assertEquals((first as SongEncyclopediaResult.Success).data, (second as SongEncyclopediaResult.Success).data)
    }

    @Test
    fun `a different song key is not served from another song cache entry`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { SUMMARY_JSON }, creators = { CREATORS_JSON })
        val repository = repository(api)

        repository.getEncyclopedia(song(id = 5L))
        repository.getEncyclopedia(song(id = 6L))

        assertEquals(listOf(5L, 6L), api.summarySongIds)
    }

    @Test
    fun `cache entry older than the ttl triggers a fresh request`() = runBlocking {
        var now = 0L
        val api = FakeEncyclopediaApi(summary = { SUMMARY_JSON }, creators = { CREATORS_JSON })
        val repository = repository(api, cache = SongEncyclopediaMemoryCache(nowMs = { now })) { now }

        repository.getEncyclopedia(song(id = 5L))
        now = TTL_MS
        repository.getEncyclopedia(song(id = 5L))
        assertEquals("the ttl boundary is still a hit", listOf(5L), api.summarySongIds)

        now = TTL_MS + 1L
        repository.getEncyclopedia(song(id = 5L))
        assertEquals(listOf(5L, 5L), api.summarySongIds)
    }

    // ---------- getEncyclopedia: creators degradation ----------

    @Test
    fun `a failing creator request still returns success without credits`() = runBlocking {
        val api = FakeEncyclopediaApi(
            summary = { SUMMARY_JSON },
            creators = { throw IllegalStateException("creators unavailable") }
        )
        val repository = repository(api)

        val result = repository.getEncyclopedia(song(id = 5L))

        assertTrue(result is SongEncyclopediaResult.Success)
        val data = (result as SongEncyclopediaResult.Success).data
        assertNull("creators are a bonus, never a hard failure", data.credits)
        assertEquals(listOf(5L), api.creatorSongIds)
    }

    @Test
    fun `empty creator roles degrade to no credits`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { SUMMARY_JSON }, creators = { """{"code":200,"data":{}}""" })
        val repository = repository(api)

        val data = (repository.getEncyclopedia(song(id = 5L)) as SongEncyclopediaResult.Success).data

        assertNull(data.credits)
    }

    @Test
    fun `cancellation from the creator request still propagates`() = runBlocking {
        val api = FakeEncyclopediaApi(
            summary = { SUMMARY_JSON },
            creators = { throw CancellationException("切歌取消") }
        )
        val repository = repository(api)

        val failure = runCatching { repository.getEncyclopedia(song(id = 5L)) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    // ---------- getEncyclopedia: success mapping ----------

    @Test
    fun `success maps the song key source basic info music info credits and fetched time`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { SUMMARY_JSON }, creators = { CREATORS_JSON })
        val repository = repository(api, nowMs = { 1_700_000_000_000L })
        val item = song(
            id = 5L,
            name = "起风了",
            artist = "买辣椒也用券 / 米果 / / ",
            album = "Netease|起风了",
            audioId = "1330348068"
        )

        val data = (repository.getEncyclopedia(item) as SongEncyclopediaResult.Success).data

        assertEquals(item.encyclopediaSongKey(), data.songKey)
        assertEquals("5|Netease|起风了|", data.songKey)
        assertEquals(MusicPlatform.CLOUD_MUSIC, data.source)
        assertEquals(1_700_000_000_000L, data.fetchedAtMs)
        assertEquals(1330348068L, api.summarySongIds.single())

        // 基础信息来自 SongItem：artist 以 / 分隔并 trim，空段丢弃
        assertEquals("起风了", data.basicInfo.name)
        assertEquals(listOf("买辣椒也用券", "米果"), data.basicInfo.artists)
        assertEquals("Netease|起风了", data.basicInfo.album)

        // 音乐属性来自接口原文
        assertEquals(listOf("流行-华语流行"), data.musicInfo?.genres)
        assertEquals(listOf("思念"), data.musicInfo?.tags)
        assertEquals("国语", data.musicInfo?.language)
        assertEquals(61, data.musicInfo?.bpm)
        assertEquals(emptyList<String>(), data.musicInfo?.instruments)

        assertEquals(listOf("米果"), data.credits?.lyricists)
        assertEquals(listOf("Terence Teo"), data.credits?.composers)
        assertEquals(emptyList<String>(), data.credits?.arrangers)
        assertEquals(emptyList<String>(), data.credits?.producers)
        assertEquals(
            listOf(EncyclopediaCreditRole(role = "混音", names = listOf("周天澈"))),
            data.credits?.otherRoles
        )

        assertEquals(listOf(EncyclopediaAward(title = "第2届网易云音乐原创盛典", detail = "获 年度十大歌曲")), data.awards)
        assertEquals(listOf("综艺《舞蹈风暴 第一季》插曲"), data.appearances)
        assertNull(data.description)
    }

    @Test
    fun `blank album and single artist are mapped without inventing values`() = runBlocking {
        val api = FakeEncyclopediaApi(summary = { SUMMARY_JSON }, creators = { CREATORS_JSON })
        val repository = repository(api)

        val result = repository.getEncyclopedia(
            song(id = 5L, artist = "  独唱  ", album = "", channelId = "netease")
        )
        val data = (result as SongEncyclopediaResult.Success).data

        assertEquals(listOf("独唱"), data.basicInfo.artists)
        assertNull("a blank album must stay null", data.basicInfo.album)
    }

    // ---------- getEncyclopedia: extra modules ----------

    @Test
    fun `failing song detail and artist still return success with those fields null`() = runBlocking {
        val api = FakeEncyclopediaApi(
            summary = { SUMMARY_JSON },
            creators = { CREATORS_JSON },
            songDetail = { throw IllegalStateException("detail unavailable") },
            artist = { throw IllegalStateException("artist unavailable") }
        )
        val repository = repository(api)

        val data = (repository.getEncyclopedia(song(id = 5L)) as SongEncyclopediaResult.Success).data

        // 主数据完好
        assertEquals(listOf("流行-华语流行"), data.musicInfo?.genres)
        assertEquals(61, data.musicInfo?.bpm)
        // 附加模块全部留空，绝不影响整页
        assertNull("album/publish fields degrade to null", data.albumId)
        assertNull(data.albumCompany)
        assertNull(data.publishTimeMs)
        assertNull(data.albumPicUrl)
        assertNull(data.artistId)
        assertNull("artist info degrades to null", data.artist)
        assertEquals(emptyList<EncyclopediaSimilarSong>(), data.similarSongs)
        assertEquals(emptyList<EncyclopediaRelatedPlaylist>(), data.relatedPlaylists)
        assertEquals(listOf(5L), api.songDetailSongIds)
        assertTrue("artist is skipped without a valid artist id", api.artistIds.isEmpty())
    }

    @Test
    fun `song detail and artist fill the extra encyclopedia fields`() = runBlocking {
        val api = FakeEncyclopediaApi(
            summary = { SUMMARY_JSON },
            creators = { CREATORS_JSON },
            songDetail = { SONG_DETAIL_JSON },
            artist = { ARTIST_JSON }
        )
        val repository = repository(api)

        val data = (repository.getEncyclopedia(song(id = 5L)) as SongEncyclopediaResult.Success).data

        assertEquals(1530547200000L, data.publishTimeMs)
        assertEquals("哇唧唧哇", data.albumCompany)
        assertEquals(7255L, data.albumId)
        assertEquals("https://p1.music.126.net/cover.jpg", data.albumPicUrl)
        assertEquals(12138269L, data.artistId)
        val artist = requireNotNull(data.artist)
        assertEquals(12138269L, artist.artistId)
        assertEquals("买辣椒也用券", artist.name)
        assertEquals(10, artist.albumSize)
        assertEquals(50, artist.musicSize)
        assertNull("fans count has no source at all", artist.fansCount)
        assertEquals(listOf(12138269L), api.artistIds)
    }

    @Test
    fun `similar songs and related playlists are parsed from the same summary response`() =
        runBlocking {
            val api = FakeEncyclopediaApi(
                summary = { SUMMARY_WITH_EXTRA_BLOCKS_JSON },
                creators = { CREATORS_JSON }
            )
            val repository = repository(api)

            val data = (
                repository.getEncyclopedia(song(id = 5L)) as SongEncyclopediaResult.Success
                ).data

            // summary 只请求一次，相似歌曲与相关歌单复用同一份原始响应
            assertEquals(listOf(5L), api.summarySongIds)
            val similar = data.similarSongs.single()
            assertEquals(186016L, similar.songId)
            assertEquals("晴天", similar.name)
            assertEquals("周杰伦", similar.artist)
            assertEquals("https://p1.music.126.net/cover1.jpg", similar.coverUrl)
            val playlist = data.relatedPlaylists.single()
            assertEquals(456L, playlist.playlistId)
            assertEquals("华语流行精选", playlist.name)
            assertEquals(6813346L, playlist.playCount)
            assertNull("playlist track count has no source", playlist.trackCount)
        }

    @Test
    fun `cancellation from the song detail request still propagates`() = runBlocking {
        val api = FakeEncyclopediaApi(
            summary = { SUMMARY_JSON },
            creators = { CREATORS_JSON },
            songDetail = { throw CancellationException("切歌取消") }
        )
        val repository = repository(api)

        val failure = runCatching { repository.getEncyclopedia(song(id = 5L)) }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    // ---------- helpers ----------

    private fun repository(
        api: NeteaseEncyclopediaApi,
        cache: SongEncyclopediaMemoryCache = SongEncyclopediaMemoryCache(),
        nowMs: () -> Long = System::currentTimeMillis
    ) = NeteaseSongEncyclopediaRepository(api = api, cache = cache, nowMs = nowMs)

    private fun song(
        id: Long,
        name: String = "歌名",
        artist: String = "歌手",
        album: String = "Netease|专辑",
        mediaUri: String? = null,
        channelId: String? = null,
        audioId: String? = null,
        matchedSongId: String? = null
    ) = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = 0L,
        durationMs = 1_000L,
        coverUrl = null,
        mediaUri = mediaUri,
        matchedSongId = matchedSongId,
        channelId = channelId,
        audioId = audioId
    )

    private class FakeEncyclopediaApi(
        private val summary: suspend (Long) -> String = { error("summary must not be called") },
        private val creators: suspend (Long) -> String = { error("creators must not be called") },
        private val songDetail: suspend (Long) -> String = { error("songDetail must not be called") },
        private val artist: suspend (Long) -> String = { error("artist must not be called") }
    ) : NeteaseEncyclopediaApi {
        val summarySongIds = mutableListOf<Long>()
        val creatorSongIds = mutableListOf<Long>()
        val songDetailSongIds = mutableListOf<Long>()
        val artistIds = mutableListOf<Long>()

        override suspend fun loadSummary(songId: Long): String {
            summarySongIds += songId
            return summary(songId)
        }

        override suspend fun loadCreators(songId: Long): String {
            creatorSongIds += songId
            return creators(songId)
        }

        override suspend fun loadSongDetail(songId: Long): String {
            songDetailSongIds += songId
            return songDetail(songId)
        }

        override suspend fun loadArtist(artistId: Long): String {
            artistIds += artistId
            return artist(artistId)
        }
    }

    private companion object {
        const val TTL_MS = 10 * 60 * 1000L

        val ONLY_RELATED_PLAYLIST_JSON =
            """{"code":200,"data":{"blocks":[{"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST"}]}}"""

        val SUMMARY_JSON = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_MUSIC_MEMORY"},
              {"code":"SONG_PLAY_ABOUT_SONG_BASIC","creatives":[
                {"creativeType":"songTag","resources":[{"uiElement":{"mainTitle":{"title":"流行-华语流行"}}}]},
                {"creativeType":"songBizTag","resources":[{"uiElement":{"mainTitle":{"title":"思念"}}}]},
                {"creativeType":"language","uiElement":{"textLinks":[{"text":"国语"}]}},
                {"creativeType":"bpm","uiElement":{"textLinks":[{"text":"61"}]}},
                {"creativeType":"songAward","resources":[
                  {"uiElement":{"mainTitle":{"title":"第2届网易云音乐原创盛典"},
                    "subTitles":[{"title":"获"},{"title":"年度十大歌曲"}]}}
                ]},
                {"creativeType":"entertainment","resources":[
                  {"uiElement":{"mainTitle":{"title":"综艺《舞蹈风暴 第一季》插曲"}}}
                ]}
              ]}
            ]}}
        """.trimIndent()

        val CREATORS_JSON = """
            {"code":200,"data":{"songCreatorsRoleVos":[
              {"roleName":"作词","creatorMetaVOS":[{"artistName":"米果"}]},
              {"roleName":"作曲","creatorMetaVOS":[{"artistName":"Terence Teo"}]},
              {"roleName":"混音","creatorMetaVOS":[{"artistName":"周天澈"}]}
            ]}}
        """.trimIndent()

        val SONG_DETAIL_JSON = """
            {"code":200,"songs":[{
              "id":186016,"name":"起风了","duration":325000,"publishTime":1530547200000,
              "artists":[{"id":12138269,"name":"买辣椒也用券"}],
              "album":{"id":7255,"name":"起风了",
                "picUrl":"https://p1.music.126.net/cover.jpg","company":"哇唧唧哇"}
            }]}
        """.trimIndent()

        val ARTIST_JSON = """
            {"code":200,"artist":{
              "id":12138269,"name":"买辣椒也用券",
              "picUrl":"https://p1.music.126.net/avatar.jpg",
              "briefDesc":"唱作音乐人。","albumSize":10,"musicSize":50
            }}
        """.trimIndent()

        /** 在 summary 基础上追加相似歌曲与相关歌单板块，用于验证「一次请求解析三个模块」。 */
        val SUMMARY_WITH_EXTRA_BLOCKS_JSON = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_MUSIC_MEMORY"},
              {"code":"SONG_PLAY_ABOUT_SONG_BASIC","creatives":[
                {"creativeType":"songTag","resources":[{"uiElement":{"mainTitle":{"title":"流行-华语流行"}}}]},
                {"creativeType":"songBizTag","resources":[{"uiElement":{"mainTitle":{"title":"思念"}}}]},
                {"creativeType":"language","uiElement":{"textLinks":[{"text":"国语"}]}},
                {"creativeType":"bpm","uiElement":{"textLinks":[{"text":"61"}]}},
                {"creativeType":"songAward","resources":[
                  {"uiElement":{"mainTitle":{"title":"第2届网易云音乐原创盛典"},
                    "subTitles":[{"title":"获"},{"title":"年度十大歌曲"}]}}
                ]},
                {"creativeType":"entertainment","resources":[
                  {"uiElement":{"mainTitle":{"title":"综艺《舞蹈风暴 第一季》插曲"}}}
                ]}
              ]},
              {"code":"SONG_PLAY_ABOUT_SIMILAR_SONG","creatives":[
                {"resources":[{"resourceType":"SONG","valid":true,"resourceId":186016,
                  "uiElement":{"mainTitle":{"title":"晴天"},"subTitles":[{"title":"周杰伦"}],
                    "images":[{"imageUrl":"http://p1.music.126.net/cover1.jpg"}]}}]}
              ]},
              {"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST","creatives":[
                {"resources":[{"resourceType":"PLAYLIST","resourceId":456,
                  "resourceExt":{"playCount":6813346},
                  "uiElement":{"mainTitle":{"title":"华语流行精选"},
                    "images":[{"imageUrl":"http://p1.music.126.net/list.jpg"}]}}]}
              ]}
            ]}}
        """.trimIndent()
    }
}
