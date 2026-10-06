package moe.ouom.neriplayer.platform.youtube.api.client

import java.io.IOException
import java.util.Locale
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorBrowseEndpoint
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicCreatorSummary
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicLyrics
import moe.ouom.neriplayer.data.model.youtube.music.YouTubeMusicVideoMetadata
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class YouTubeMusicClientBrowseTest {
    private val originalLocale = Locale.getDefault()
    private val creator = YouTubeMusicCreatorSummary("UCcreator", "Demo Creator", "Artist", "https://img/creator")
    private val topSongs = creatorPage("TOP SONGS", "top-song" to "Top Song")

    @Before
    fun pinLocale() {
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `creator detail requires a browse id before any request`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val http = YouTubeMusicTestHttp { error("No request expected") }
        val client = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), http.client, refresher)

        val error = runCatching { client.getCreatorDetail(creator.copy(browseId = " ")) }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("YouTube Music creator browseId is required", error?.message)
        assertTrue(refresher.calls.isEmpty())
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun `creator detail uses the saved login when present and the guest bootstrap otherwise`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val login = YouTubeMusicTestHttp { call -> if (call.isBootstrap) bootstrapPage(loggedIn = true) else json(topSongs) }
        val guest = YouTubeMusicTestHttp { call -> if (call.isBootstrap) bootstrapPage() else json(topSongs) }

        val loginDetail = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), login.client, refresher).getCreatorDetail(creator)
        val guestDetail = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), guest.client).getCreatorDetail(creator)

        assertEquals(listOf("creator_detail" to false), refresher.calls)
        listOf(loginDetail, guestDetail).forEach { detail ->
            assertEquals("UCcreator", detail.header.browseId)
            assertEquals("Demo Creator", detail.header.title)
            assertEquals("https://img/creator", detail.header.coverUrl)
            assertEquals(listOf("TOP SONGS"), detail.sections.map { it.title })
            assertEquals(listOf("top-song"), detail.sections.single().items.map { it.videoId })
        }
        assertEquals("UCcreator", login.innertubeCalls("browse").single().payload!!.getString("browseId"))
        assertTrue(login.innertubeCalls("browse").single().request.header("Authorization")!!.startsWith("SAPISIDHASH "))
        assertNull(guest.innertubeCalls("browse").single().request.header("Authorization"))
    }

    @Test
    fun `creator items send optional params and continuations reuse the bootstrap`() = runTest {
        val itemsPage = browseTab(
            """{"musicPlaylistShelfRenderer":{"title":{"simpleText":"All songs"},"contents":[${songRow("song-1", "Song 1")}],""" +
                """"continuations":[{"nextContinuationData":{"continuation":"next-songs"}}]}}"""
        )
        val continuationPage = """{"continuationContents":{"gridContinuation":{"items":[${songRow("song-2", "Song 2")}]}}}"""
        val refresher = RecordingYouTubeRefresher()
        val login = YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage(loggedIn = true)
                call.payload!!.has("continuation") -> json(continuationPage)
                else -> json(itemsPage)
            }
        }
        val loginClient = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), login.client, refresher)

        val page = loginClient.getCreatorItems(YouTubeMusicCreatorBrowseEndpoint("UCcreator", "songs-param"), "TOP SONGS")
        val next = loginClient.getCreatorItemsContinuation(page.continuation!!)

        assertEquals("All songs", page.title)
        assertEquals(listOf("song-1"), page.items.map { it.videoId })
        assertEquals(listOf("song-2"), next.items.map { it.videoId })
        assertEquals(listOf("creator_items" to false, "creator_items_continuation" to false), refresher.calls)
        assertEquals(1, login.bootstrapCalls().size)
        val (itemsRequest, continuationRequest) = login.innertubeCalls("browse").map { it.payload!! }
        assertEquals("songs-param", itemsRequest.getString("params"))
        assertEquals("next-songs", continuationRequest.getString("continuation"))
        assertFalse(continuationRequest.has("browseId"))

        val guest = YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage()
                call.payload!!.has("continuation") -> json(continuationPage)
                else -> json(browseTab())
            }
        }
        val guestClient = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), guest.client)

        val fallback = guestClient.getCreatorItems(YouTubeMusicCreatorBrowseEndpoint("UCcreator"), "TOP SONGS")
        guestClient.getCreatorItemsContinuation("guest-token")

        assertEquals("TOP SONGS", fallback.title)
        assertTrue(fallback.items.isEmpty())
        assertFalse(guest.innertubeCalls("browse").first().payload!!.has("params"))
        assertEquals("guest-token", guest.innertubeCalls("browse").last().payload!!.getString("continuation"))
    }

    @Test
    fun `creator item lookups reject blank identifiers`() = runTest {
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), YouTubeMusicTestHttp { error("No request") }.client)

        val items = runCatching {
            client.getCreatorItems(YouTubeMusicCreatorBrowseEndpoint(" "), "Fallback")
        }.exceptionOrNull()
        val continuation = runCatching { client.getCreatorItemsContinuation("") }.exceptionOrNull()

        assertEquals("YouTube Music creator items browseId is required", items?.message)
        assertEquals("YouTube Music creator items continuation is required", continuation?.message)
    }

    @Test
    fun `playlist detail adds the VL prefix only to bare playlist ids`() = runTest {
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) bootstrapPage() else json("""{"contents":{"twoColumnBrowseResultsRenderer":{}}}""")
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client)

        val details = listOf(" PLabc ", "VLPLready", "MPREb_album", "FEmusic_liked_videos", " ").map {
            client.getPlaylistDetailPreview(it, fallbackTitle = "Fallback title")
        }

        assertEquals(
            listOf("VLPLabc", "VLPLready", "MPREb_album", "FEmusic_liked_videos", ""),
            http.innertubeCalls("browse").map { it.payload!!.getString("browseId") }
        )
        assertEquals(listOf("VLPLabc", "VLPLready", "MPREb_album", "FEmusic_liked_videos", ""), details.map { it.browseId })
        assertTrue(details.all { it.title == "Fallback title" && it.tracks.isEmpty() && it.fullyLoaded })
        assertEquals(1, http.bootstrapCalls().size)
    }

    @Test
    fun `logged in playlist detail refreshes auth before using the authenticated bootstrap`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) bootstrapPage(loggedIn = true) else json("""{"contents":{}}""")
        }

        val detail = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), http.client, refresher).getPlaylistDetail("PLmine")

        assertEquals("VLPLmine", detail.browseId)
        assertEquals(listOf("playlist_detail" to false), refresher.calls)
        assertTrue(http.innertubeCalls("browse").single().request.header("Authorization")!!.startsWith("SAPISIDHASH "))
    }

    @Test
    fun `browse falls back to the safe locale when the preferred one returns an empty shell`() = runTest {
        val http = YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage()
                call.hl == "en-US" -> json("""{"responseContext":{}}""")
                else -> json(topSongs)
            }
        }

        val detail = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client).getCreatorDetail(creator)

        assertEquals(listOf("TOP SONGS"), detail.sections.map { it.title })
        assertEquals(listOf("en-US" to "US", "zh-CN" to "JP"), http.innertubeCalls("browse").map { it.hl to it.gl })
        assertEquals(1, http.bootstrapCalls().size)
    }

    @Test
    fun `browse retries an unauthorized response on a fresh bootstrap`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val withRefresher = unauthorizedOnceHttp()
        YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), withRefresher.client, refresher).getCreatorDetail(creator)

        assertEquals(listOf("creator_detail" to false, "browse_http_recoverable" to true), refresher.calls)
        assertEquals(2, withRefresher.bootstrapCalls().size)
        assertEquals(listOf("en-US", "en-US"), withRefresher.innertubeCalls("browse").map { it.hl })

        val withoutRefresher = unauthorizedOnceHttp()
        val detail = YouTubeMusicClient(FakeYouTubeAuth(LOGIN_AUTH), withoutRefresher.client).getCreatorDetail(creator)

        assertEquals(listOf("TOP SONGS"), detail.sections.map { it.title })
        assertEquals(2, withoutRefresher.bootstrapCalls().size)
    }

    @Test
    fun `browse gives up after two failed attempts for every locale`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val http = YouTubeMusicTestHttp { call -> if (call.isBootstrap) bootstrapPage() else YouTubeMusicReply("busy", 500) }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client, refresher)

        val error = runCatching { client.getCreatorDetail(creator) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertEquals("YouTube Music request failed: 500 busy", error?.message)
        assertEquals(listOf("en-US", "en-US", "zh-CN", "zh-CN"), http.innertubeCalls("browse").map { it.hl })
        assertEquals(3, http.bootstrapCalls().size)
        assertEquals(listOf("creator_detail" to false), refresher.calls)
    }

    @Test
    fun `lyrics are read from the browse id of the lyrics tab`() = runTest {
        val refresher = RecordingYouTubeRefresher()
        val http = YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage()
                call.endpoint == "next" -> json(
                    """{"contents":{"singleColumnMusicWatchNextResultsRenderer":{"tabbedRenderer":{""" +
                        """"watchNextTabbedResultsRenderer":{"tabs":[""" +
                        """{"tabRenderer":{"title":"Up Next","endpoint":{"browseEndpoint":{"browseId":"MPREb_next"}}}},""" +
                        """{"tabRenderer":{"title":"Lyrics","endpoint":{"browseEndpoint":{"browseId":"MPLYt_song"}}}}""" +
                        """]}}}}}"""
                )
                else -> json(
                    """{"contents":{"sectionListRenderer":{"contents":[{"musicDescriptionShelfRenderer":{""" +
                        """"description":{"runs":[{"text":"Line one\nLine two"}]},""" +
                        """"footer":{"runs":[{"text":"Source: Musixmatch"}]}}}]}}}"""
                )
            }
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client, refresher)

        val lyrics = client.getLyrics("song-id")

        assertEquals(YouTubeMusicLyrics("Line one\nLine two", "Source: Musixmatch"), lyrics)
        assertEquals(listOf("lyrics" to false), refresher.calls)
        val next = http.innertubeCalls("next").single().payload!!
        assertEquals("song-id", next.getString("videoId"))
        assertTrue(next.getBoolean("isAudioOnly"))
        assertEquals("MPLYt_song", http.innertubeCalls("browse").single().payload!!.getString("browseId"))
    }

    @Test
    fun `video metadata comes from oembed and failures resolve to null`() = runTest {
        var reply = json("""{"title":"Song","author_name":"Artist","thumbnail_url":"https://i.ytimg.com/vi/id/hq.jpg"}""")
        val http = YouTubeMusicTestHttp { reply }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client)

        assertNull(client.getVideoMetadata("  "))
        assertTrue(http.calls.isEmpty())
        assertEquals(
            YouTubeMusicVideoMetadata("Song", "Artist", "https://i.ytimg.com/vi/id/hq.jpg"),
            client.getVideoMetadata(" dQw4w9WgXcQ ")
        )
        reply = YouTubeMusicReply("missing", 404)
        assertNull(client.getVideoMetadata("gone"))
        reply = json("not json")
        assertNull(client.getVideoMetadata("broken"))

        val request = http.calls.first().request
        assertEquals("/oembed", request.url.encodedPath)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", request.url.queryParameter("url"))
        assertEquals("json", request.url.queryParameter("format"))
        assertEquals("application/json", request.header("Accept"))
        assertEquals(3, http.calls.size)
    }

    @Test
    fun `debug browse reports the request envelope and the response shape`() = runTest {
        val http = YouTubeMusicTestHttp { call ->
            if (call.isBootstrap) bootstrapPage() else json("""{"contents":{},"responseContext":{}}""")
        }
        val client = YouTubeMusicClient(FakeYouTubeAuth(GUEST_AUTH), http.client)

        val result = client.debugBrowseRaw("FEmusic_home", hl = " ja ", gl = "jp")

        assertEquals("browse ok, browseId=FEmusic_home", result.summary)
        val raw = JSONObject(result.rawJson)
        assertEquals("browse", raw.getString("probe"))
        val request = raw.getJSONObject("request")
        assertEquals("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false&key=test-key", request.getString("url"))
        assertEquals("ja", request.getJSONObject("locale").getString("hl"))
        assertEquals("JP", request.getJSONObject("locale").getString("gl"))
        assertEquals("FEmusic_home", request.getJSONObject("payload").getString("browseId"))
        val parsed = raw.getJSONObject("parsed")
        assertTrue(parsed.getBoolean("hasContents"))
        assertFalse(parsed.getBoolean("hasContinuationContents"))
        val keys = parsed.getJSONArray("topLevelKeys")
        assertEquals(setOf("contents", "responseContext"), (0 until keys.length()).map(keys::getString).toSet())
        assertEquals("ja" to "JP", http.innertubeCalls("browse").single().let { it.hl to it.gl })
    }

    private fun unauthorizedOnceHttp(): YouTubeMusicTestHttp {
        var browseAttempts = 0
        return YouTubeMusicTestHttp { call ->
            when {
                call.isBootstrap -> bootstrapPage(loggedIn = true)
                ++browseAttempts == 1 -> YouTubeMusicReply("login expired", 401)
                else -> json(topSongs)
            }
        }
    }
}
