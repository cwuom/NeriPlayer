package moe.ouom.neriplayer.platform.bilibili.lyrics

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleCue
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class BiliSubtitleLyricsLoaderTest {
    @Before
    fun clearTrackCache() {
        BiliSubtitleLyricsLoader.clearCachedTracks()
    }

    @Test
    fun `anonymous session reads no subtitle tracks`() = runTest {
        val calls = mutableListOf<String>()
        val client = fakeClient(login = false, calls = calls)

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertTrue(lyrics.lyrics.isEmpty())
        assertEquals(listOf("hasLoginSession"), calls)
    }

    @Test
    fun `primary track becomes lyrics and the other language becomes translation`() = runTest {
        val zh = track("zh-CN", ZH_URL)
        val en = track("en-US", EN_URL)
        val client = fakeClient(
            login = true,
            tracks = listOf(zh, en),
            cues = mapOf(
                ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")),
                EN_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "hello"))
            )
        )

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertEquals(listOf("你好"), lyrics.lyrics.map { it.text })
        assertEquals(listOf("hello"), lyrics.translatedLyrics.map { it.text })
        assertEquals("zh-CN", lyrics.track?.lan)
        assertEquals("en-US", lyrics.translatedTrack?.lan)
        assertEquals(101L, lyrics.cid)
    }

    @Test
    fun `single language subtitles keep no translation track`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("zh-CN", ZH_URL)),
            cues = mapOf(ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")))
        )

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertEquals(listOf("你好"), lyrics.lyrics.map { it.text })
        assertTrue(lyrics.translatedLyrics.isEmpty())
        assertNull(lyrics.translatedTrack)
    }

    @Test
    fun `preferred language selects the requested track`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("zh-CN", ZH_URL), track("en-US", EN_URL)),
            cues = mapOf(
                ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")),
                EN_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "hello"))
            )
        )

        val lyrics = BiliSubtitleLyricsLoader.load(
            biliSong(),
            client,
            preferredLanguage = "en-US"
        )

        assertEquals(listOf("hello"), lyrics.lyrics.map { it.text })
        assertEquals("en-US", lyrics.track?.lan)
    }

    @Test
    fun `preferred language falls back to the automatic selection`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("zh-CN", ZH_URL)),
            cues = mapOf(ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")))
        )

        val lyrics = BiliSubtitleLyricsLoader.load(
            biliSong(),
            client,
            preferredLanguage = "ja"
        )

        assertEquals(listOf("你好"), lyrics.lyrics.map { it.text })
        assertEquals("zh-CN", lyrics.track?.lan)
    }

    @Test
    fun `video without subtitles yields an empty result`() = runTest {
        val client = fakeClient(login = true, tracks = emptyList())

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertTrue(lyrics.lyrics.isEmpty())
        assertEquals(101L, lyrics.cid)
    }

    @Test
    fun `ai subtitles stay unusable when they are the only track and ai is disabled`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("ai-zh", AI_URL, isAi = true)),
            cues = mapOf(AI_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "自动字幕")))
        )

        val disabled = BiliSubtitleLyricsLoader.load(
            biliSong(),
            client,
            aiSubtitleAllowed = false
        )
        assertTrue(disabled.lyrics.isEmpty())

        val enabled = BiliSubtitleLyricsLoader.load(
            biliSong(),
            client,
            aiSubtitleAllowed = true
        )
        assertEquals(listOf("自动字幕"), enabled.lyrics.map { it.text })
        assertEquals("ai-zh", enabled.track?.lan)
    }

    @Test
    fun `unreadable preferred track falls back to another track`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("en-US", EN_URL), track("zh-CN", ZH_URL)),
            cues = mapOf(ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")))
        )

        val lyrics = BiliSubtitleLyricsLoader.load(
            biliSong(),
            client,
            preferredLanguage = "en-US"
        )

        assertEquals("zh-CN", lyrics.track?.lan)
        assertEquals(listOf("你好"), lyrics.lyrics.map { it.text })
    }

    @Test
    fun `ai translation track is ignored when ai subtitles are disabled`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("zh-CN", ZH_URL), track("en-US", EN_URL, isAi = true)),
            cues = mapOf(
                ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")),
                EN_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "hello"))
            )
        )

        val lyrics = BiliSubtitleLyricsLoader.load(
            biliSong(),
            client,
            aiSubtitleAllowed = false
        )

        assertEquals(listOf("你好"), lyrics.lyrics.map { it.text })
        assertTrue(lyrics.translatedLyrics.isEmpty())
        assertNull(lyrics.translatedTrack)
    }

    @Test
    fun `automatic fallback to a later track is not treated as the preferred result`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("zh-CN", ZH_URL), track("en-US", EN_URL)),
            cues = mapOf(EN_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "hello")))
        )

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertEquals(listOf("hello"), lyrics.lyrics.map { it.text })
        assertFalse(lyrics.preferredTrackMatched)
    }

    @Test
    fun `missing translation cues mark the result incomplete`() = runTest {
        val client = fakeClient(
            login = true,
            tracks = listOf(track("zh-CN", ZH_URL), track("en-US", EN_URL)),
            cues = mapOf(
                ZH_URL to listOf(BiliSubtitleCue(1_000L, 2_000L, "你好")),
                EN_URL to emptyList()
            )
        )

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertEquals(listOf("你好"), lyrics.lyrics.map { it.text })
        assertTrue(lyrics.translatedLyrics.isEmpty())
        assertFalse(lyrics.translationComplete)
    }

    private fun fakeClient(
        login: Boolean,
        tracks: List<BiliSubtitleTrack> = emptyList(),
        cues: Map<String, List<BiliSubtitleCue>> = emptyMap(),
        calls: MutableList<String> = mutableListOf()
    ): BiliClient = Mockito.mock(BiliClient::class.java, Answer { invocation ->
        val name = invocation.method.name
        calls += name
        when (name) {
            "hasLoginSession" -> login
            "getPlayerSubtitlePayload" -> subtitleTracksPayload(tracks)
            "getSubtitlePayload" -> subtitleCuesPayload(
                cues[invocation.getArgument<String>(0)].orEmpty()
            )
            else -> Answers.RETURNS_DEFAULTS.answer(invocation)
        }
    })

    private fun biliSong(): SongItem = SongItem(
        id = 170001L,
        name = "Signal",
        artist = "UP",
        album = "Bilibili|101|BV17x411w7KC",
        albumId = 0L,
        durationMs = 240_000L,
        coverUrl = null,
        channelId = "bilibili",
        audioId = "170001",
        subAudioId = "101"
    )

    private fun subtitleTracksPayload(tracks: List<BiliSubtitleTrack>): JSONObject {
        val items = JSONArray()
        tracks.forEach { track ->
            items.put(
                JSONObject()
                    .put("id", track.id)
                    .put("lan", track.lan)
                    .put("lan_doc", track.lanDoc)
                    .put("subtitle_url", track.url)
                    .put("type", if (track.isAiGenerated) 1 else 0)
            )
        }
        return JSONObject().put("subtitles", items)
    }

    private fun subtitleCuesPayload(cues: List<BiliSubtitleCue>): JSONObject {
        val items = JSONArray()
        cues.forEach { cue ->
            items.put(
                JSONObject()
                    .put("from", cue.startTimeMs / 1000.0)
                    .put("to", cue.endTimeMs / 1000.0)
                    .put("content", cue.content)
            )
        }
        return JSONObject().put("body", items)
    }

    private fun track(lan: String, url: String, isAi: Boolean = false): BiliSubtitleTrack =
        BiliSubtitleTrack(
            id = lan.hashCode().toLong(),
            lan = lan,
            lanDoc = lan,
            url = url,
            isAiGenerated = isAi
        )

    private companion object {
        const val ZH_URL = "https://example.invalid/zh.json"
        const val EN_URL = "https://example.invalid/en.json"
        const val AI_URL = "https://example.invalid/ai-zh.json"
    }
}
