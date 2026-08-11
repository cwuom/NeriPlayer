package moe.ouom.neriplayer.ui.viewmodel.tab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.data.model.SongItem

class NeteaseHomeRecommendationsTest {

    @Test
    fun parseDailyRecommendedSongs_readsDailySongsShape() {
        val raw = """
            {
              "code": 200,
              "data": {
                "dailySongs": [
                  {
                    "id": 1001,
                    "name": "每日一首",
                    "dt": 180000,
                    "ar": [{"id": 11, "name": "歌手 A"}],
                    "al": {
                      "id": 21,
                      "name": "专辑 A",
                      "picUrl": "http://p1.music.126.net/a.jpg"
                    }
                  }
                ]
              }
            }
        """.trimIndent()

        val songs = parseNeteaseHomeSongs(raw)

        assertEquals(1, songs.size)
        assertEquals(1001L, songs[0].id)
        assertEquals("每日一首", songs[0].name)
        assertEquals("歌手 A", songs[0].artist)
        assertEquals("专辑 A", songs[0].album)
        assertEquals("https://p1.music.126.net/a.jpg", songs[0].coverUrl)
    }

    @Test
    fun parsePersonalizedNewSongs_readsNestedSongShape() {
        val raw = """
            {
              "code": 200,
              "result": [
                {
                  "song": {
                    "id": 2002,
                    "name": "新歌推荐",
                    "duration": 210000,
                    "artists": [{"id": 31, "name": "歌手 B"}],
                    "album": {
                      "id": 41,
                      "name": "专辑 B",
                      "picUrl": "https://p2.music.126.net/b.jpg"
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val songs = parseNeteaseHomeSongs(raw)

        assertEquals(1, songs.size)
        assertEquals(2002L, songs[0].id)
        assertEquals("新歌推荐", songs[0].name)
        assertEquals("歌手 B", songs[0].artist)
        assertEquals(210000L, songs[0].durationMs)
    }

    @Test
    fun parsePlaylistSources_readsRecommendAndPlaylistDetailShapes() {
        val dailyRaw = """
            {
              "code": 200,
              "recommend": [
                {
                  "id": 3003,
                  "name": "每日歌单",
                  "coverImgUrl": "http://p3.music.126.net/c.jpg",
                  "playcount": 123456,
                  "songCount": 32
                }
              ]
            }
        """.trimIndent()
        val detailRaw = """
            {
              "code": 200,
              "playlist": {
                "id": 5320167908,
                "name": "时光雷达",
                "coverImgUrl": "http://p4.music.126.net/d.jpg",
                "playCount": 654321,
                "trackCount": 50
              }
            }
        """.trimIndent()

        val playlists = parseNeteaseHomePlaylists(dailyRaw)
        val detail = parseNeteasePlaylistDetailSummary(
            raw = detailRaw,
            fallback = NeteaseRadarPlaylistDefinition(id = 5320167908L, name = "时光雷达")
        )

        assertEquals(3003L, playlists.single().id)
        assertEquals("https://p3.music.126.net/c.jpg", playlists.single().picUrl)
        assertEquals(32, playlists.single().trackCount)
        assertEquals(5320167908L, detail.id)
        assertEquals("时光雷达", detail.name)
        assertEquals("https://p4.music.126.net/d.jpg", detail.picUrl)
    }

    @Test
    fun radarPlaylistDefinitions_keepOfficialRadarIds() {
        assertEquals(
            listOf(5320167908L, 5362359247L, 5300458264L, 5327906368L, 6700242542L),
            NeteaseRadarPlaylistDefinitions.map { it.id }
        )
        assertEquals("雷击顿唱片行", NeteaseRadarPlaylistDefinitions.last().name)
    }

    @Test
    fun availableSources_showAllPublicFeedsWhenSignedOut() {
        val songs = availableNeteaseHomeSongSources(
            candidates = NeteaseHomeRadarSongSources,
            hasLogin = false
        )
        val playlists = availableNeteaseHomePlaylistSources(
            candidates = NeteaseHomePlaylistSources,
            hasLogin = false
        )

        assertEquals(
            listOf(NeteaseHomeSongSource.PERSONAL_RADAR),
            songs
        )
        assertEquals(
            listOf(
                NeteaseHomePlaylistSource.PERSONALIZED,
                NeteaseHomePlaylistSource.HIGH_QUALITY,
                NeteaseHomePlaylistSource.HOT_PLAYLISTS,
                NeteaseHomePlaylistSource.ACG_PLAYLISTS
            ),
            playlists
        )
        assertTrue(songs.none { it.requiresLogin })
        assertTrue(playlists.none { it.requiresLogin })
    }

    @Test
    fun availableSources_addPersonalFeedsWhenSignedIn() {
        val songs = availableNeteaseHomeSongSources(
            candidates = NeteaseHomeRadarSongSources,
            hasLogin = true
        )
        val playlists = availableNeteaseHomePlaylistSources(
            candidates = NeteaseHomePlaylistSources,
            hasLogin = true
        )

        assertEquals(
            listOf(
                NeteaseHomeSongSource.PERSONAL_RADAR,
                NeteaseHomeSongSource.DAILY_RECOMMEND,
                NeteaseHomeSongSource.PRIVATE_FM
            ),
            songs
        )
        assertTrue(playlists.contains(NeteaseHomePlaylistSource.DAILY_RESOURCE))
    }

    @Test
    fun appendPrivateFmSongs_deduplicatesByAudioIdAndHonorsLimit() {
        val first = testSong(id = 1, audioId = "1")
        val duplicate = testSong(id = 1, audioId = "1")
        val second = testSong(id = 2, audioId = "2")

        val merged = appendUniqueNeteaseHomeSongs(
            current = listOf(first),
            next = listOf(duplicate, second),
            limit = 2
        )

        assertEquals(listOf(first, second), merged)
    }

    @Test
    fun privateFmDisablesOuterRetry() {
        assertEquals(1, homeSongFetchAttemptCount(NeteaseHomeSongSource.PRIVATE_FM))
        assertEquals(3, homeSongFetchAttemptCount(NeteaseHomeSongSource.DAILY_RECOMMEND))
    }

    @Test
    fun cookieUpdateRefreshesHomeForLoginChangesOrInitialBootstrap() {
        assertTrue(
            shouldRefreshNeteaseHome(
                loginChanged = true,
                recommendationsBootstrapped = false
            )
        )
        assertTrue(
            shouldRefreshNeteaseHome(
                loginChanged = true,
                recommendationsBootstrapped = true
            )
        )
        assertTrue(
            shouldRefreshNeteaseHome(
                loginChanged = false,
                recommendationsBootstrapped = false
            )
        )
        assertFalse(
            shouldRefreshNeteaseHome(
                loginChanged = false,
                recommendationsBootstrapped = true
            )
        )
    }

    private fun testSong(id: Long, audioId: String): SongItem {
        return SongItem(
            id = id,
            name = "song-$id",
            artist = "artist",
            album = "album",
            albumId = id,
            durationMs = 180_000L,
            coverUrl = null,
            channelId = "netease",
            audioId = audioId
        )
    }
}
