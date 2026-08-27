package moe.ouom.neriplayer.ui.viewmodel.tab

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.coroutineContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.ouom.neriplayer.core.api.netease.mergeNeteaseSessionCookies
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
            listOf(5320167908L, 5362359247L, 5300458264L, 5327906368L, 5341776086L),
            NeteaseRadarPlaylistDefinitions.map { it.id }
        )
        assertEquals("神秘雷达", NeteaseRadarPlaylistDefinitions.last().name)
    }

    @Test
    fun parsePlaylistDetail_readsMgcResultWithAccountSpecificRadarTitleAndCover() {
        val detail = parseNeteasePlaylistDetailSummary(
            raw = """
                {
                  "code": 200,
                  "result": {
                    "id": 5327906368,
                    "name": "为你定制的乐迷雷达",
                    "coverImgUrl": "http://p1.music.126.net/account-radar.jpg",
                    "playCount": 42,
                    "trackCount": 30
                  }
                }
            """.trimIndent(),
            fallback = NeteaseRadarPlaylistDefinition(
                id = 5327906368L,
                name = "乐迷雷达"
            )
        )

        assertEquals("为你定制的乐迷雷达", detail.name)
        assertEquals("https://p1.music.126.net/account-radar.jpg", detail.picUrl)
        assertEquals(42L, detail.playCount)
    }

    @Test
    fun parsePlaylistDetailOrNull_rejectsEmptySuccessfulResponse() {
        assertNull(parseNeteasePlaylistDetailSummaryOrNull("""{"code":200}"""))
    }

    @Test
    fun radarPlaylistSummaries_keepDefaultMetadataForEmptyOrMismatchedResponse() = runTest {
        val first = NeteaseRadarPlaylistDefinitions[0]
        val second = NeteaseRadarPlaylistDefinitions[1]

        val summaries = loadNeteaseRadarPlaylistSummaries(
            definitions = listOf(first, second),
            loadMetadata = { playlistId ->
                if (playlistId == first.id) {
                    """{"code":200}"""
                } else {
                    """
                        {
                          "code": 200,
                          "result": {
                            "id": ${first.id},
                            "name": "wrong radar",
                            "coverImgUrl": "https://example.com/wrong.jpg"
                          }
                        }
                    """.trimIndent()
                }
            }
        )

        assertEquals(listOf(first.id, second.id), summaries.map { it.id })
        assertEquals(first.name, summaries[0].name)
        assertEquals(second.name, summaries[1].name)
        assertTrue(summaries.all { it.picUrl.isEmpty() })
    }

    @Test
    fun radarPlaylistSummaries_useMgcMetadataForFanAndMysteryRadar() = runTest {
        val requestedIds = mutableListOf<Long>()

        val summaries = loadNeteaseRadarPlaylistSummaries(
            definitions = NeteaseRadarPlaylistDefinitions,
            loadMetadata = { playlistId ->
                requestedIds += playlistId
                val metadata = when (playlistId) {
                    NETEASE_FAN_RADAR_PLAYLIST_ID -> "为你定制的乐迷雷达" to "fan"
                    5_341_776_086L -> "神秘歌友推荐你听《尘缘》|神秘雷达" to "mystery"
                    else -> "雷达 $playlistId" to "default"
                }
                """
                    {
                      "code": 200,
                      "result": {
                        "id": $playlistId,
                        "name": "${metadata.first}",
                        "coverImgUrl": "https://example.com/${metadata.second}.jpg",
                        "playCount": 42,
                        "trackCount": 50
                      }
                    }
                """.trimIndent()
            }
        )

        assertEquals(NeteaseRadarPlaylistDefinitions.map { it.id }, summaries.map { it.id })
        assertEquals("为你定制的乐迷雷达", summaries[3].name)
        assertEquals("https://example.com/fan.jpg", summaries[3].picUrl)
        assertEquals("神秘歌友推荐你听《尘缘》|神秘雷达", summaries[4].name)
        assertEquals("https://example.com/mystery.jpg", summaries[4].picUrl)
        assertEquals(NeteaseRadarPlaylistDefinitions.map { it.id }, requestedIds)
        assertTrue(requestedIds.contains(NETEASE_FAN_RADAR_PLAYLIST_ID))
    }

    @Test
    fun mergeRadarSessionCookies_keepsOnlyRequiredRuntimeContext() {
        val cookies = mergeNeteaseSessionCookies(
            persistedCookies = mapOf(
                "MUSIC_U" to "login-cookie",
                "NMTID" to "old-context"
            ),
            runtimeCookies = mapOf(
                "NMTID" to "new-context",
                "__csrf" to "new-csrf",
                "unrelated" to "ignored"
            )
        )

        assertEquals("login-cookie", cookies["MUSIC_U"])
        assertEquals("new-context", cookies["NMTID"])
        assertEquals("new-csrf", cookies["__csrf"])
        assertFalse(cookies.containsKey("unrelated"))
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun homeSectionLoadCoordinator_publishesCompletedSourcesWithoutWaitingForEarlierSources() = runTest {
        val coordinator = HomeSectionLoadCoordinator(maxConcurrentLoads = 2)
        val first = CompletableDeferred<Int>()
        val second = CompletableDeferred<Int>()
        val published = mutableListOf<Int>()
        val load = async {
            coordinator.load(
                group = HomeSectionLoadGroup.PLAYLISTS,
                sources = listOf("first", "second"),
                fetch = { source ->
                    if (source == "first") first.await() else second.await()
                },
                onResult = published::add
            )
        }

        runCurrent()
        second.complete(2)
        runCurrent()

        assertEquals(listOf(2), published)
        assertFalse(load.isCompleted)

        first.complete(1)
        load.await()

        assertEquals(listOf(2, 1), published)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun homeSectionLoadCoordinator_capsConcurrentSourcesAcrossGroups() = runTest {
        val coordinator = HomeSectionLoadCoordinator(
            maxConcurrentLoads = 2,
            maxConcurrentLoadsPerGroup = 1
        )
        val release = CompletableDeferred<Unit>()
        var active = 0
        var maxActive = 0
        var completed = 0
        val loads = HomeSectionLoadGroup.values().map { group ->
            async {
                coordinator.load(
                    group = group,
                    sources = listOf(1, 2),
                    fetch = { source ->
                        active += 1
                        maxActive = maxOf(maxActive, active)
                        release.await()
                        active -= 1
                        source
                    },
                    onResult = { completed += 1 }
                )
            }
        }

        runCurrent()

        assertEquals(2, active)
        assertEquals(2, maxActive)

        release.complete(Unit)
        loads.forEach { it.await() }

        assertEquals(0, active)
        assertEquals(6, completed)
        assertEquals(2, maxActive)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun homeSectionLoadCoordinator_cancellationPreventsQueuedSourcesAndPublishing() = runTest {
        val coordinator = HomeSectionLoadCoordinator(maxConcurrentLoads = 1)
        val release = CompletableDeferred<Unit>()
        val started = mutableListOf<String>()
        val published = mutableListOf<String>()
        val load = async {
            coordinator.load(
                group = HomeSectionLoadGroup.RADAR_SONGS,
                sources = listOf("running", "queued"),
                fetch = { source ->
                    started += source
                    if (source == "running") release.await()
                    source
                },
                onResult = published::add
            )
        }

        runCurrent()

        assertEquals(listOf("running"), started)

        load.cancel()
        load.join()
        release.complete(Unit)
        advanceUntilIdle()

        assertTrue(load.isCancelled)
        assertEquals(listOf("running"), started)
        assertTrue(published.isEmpty())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun radarSummaryRequestsOnlyMetadataAndStopsAfterCancellation() = runTest {
        val requested = mutableListOf<Long>()
        val result = async {
            loadNeteaseRadarPlaylistSummaries(
                definitions = NeteaseRadarPlaylistDefinitions.take(2),
                loadMetadata = { playlistId ->
                    requested += playlistId
                    coroutineContext.cancel()
                    """{"code":200}"""
                }
            )
        }

        runCurrent()

        assertTrue(result.isCancelled)
        assertEquals(
            listOf(NeteaseRadarPlaylistDefinitions.first().id),
            requested
        )
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
        assertTrue(
            shouldRefreshNeteaseHome(
                loginChanged = false,
                recommendationsBootstrapped = true,
                accountContextChanged = true
            )
        )
    }

    @Test
    fun initialCookieEmissionOnlySkipsTheUnchangedConstructionSnapshot() {
        val accountA = mapOf("MUSIC_U" to "account-a")
        val accountB = mapOf("MUSIC_U" to "account-b")

        assertFalse(
            shouldHandleInitialNeteaseHomeCookieEmission(
                isFirstEmission = true,
                initialCookies = accountA,
                emittedCookies = accountA
            )
        )
        assertTrue(
            shouldHandleInitialNeteaseHomeCookieEmission(
                isFirstEmission = true,
                initialCookies = accountA,
                emittedCookies = accountB
            )
        )
        assertTrue(
            shouldHandleInitialNeteaseHomeCookieEmission(
                isFirstEmission = false,
                initialCookies = accountA,
                emittedCookies = accountA
            )
        )
    }

    @Test
    fun radarPlaylistLoadRejectsOlderGenerationAndAccountContext() {
        assertTrue(
            shouldAcceptNeteaseRadarPlaylistLoadResult(
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestRadarCacheContext = "account-a",
                activeRadarCacheContext = "account-a"
            )
        )
        assertFalse(
            shouldAcceptNeteaseRadarPlaylistLoadResult(
                requestGeneration = 1L,
                activeGeneration = 2L,
                requestRadarCacheContext = "account-a",
                activeRadarCacheContext = "account-a"
            )
        )
        assertFalse(
            shouldAcceptNeteaseRadarPlaylistLoadResult(
                requestGeneration = 2L,
                activeGeneration = 2L,
                requestRadarCacheContext = "account-a",
                activeRadarCacheContext = "account-b"
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
