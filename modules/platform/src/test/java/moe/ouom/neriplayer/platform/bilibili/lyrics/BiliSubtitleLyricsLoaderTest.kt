package moe.ouom.neriplayer.platform.bilibili.lyrics

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleCue
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class BiliSubtitleLyricsLoaderTest {
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
    fun `video without subtitles yields an empty result`() = runTest {
        val client = fakeClient(login = true, tracks = emptyList())

        val lyrics = BiliSubtitleLyricsLoader.load(biliSong(), client)

        assertTrue(lyrics.lyrics.isEmpty())
        assertEquals(101L, lyrics.cid)
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
            "getVideoSubtitleTracks" -> tracks
            "getSubtitleCues" -> cues[invocation.getArgument<String>(0)].orEmpty()
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

    private fun track(lan: String, url: String): BiliSubtitleTrack = BiliSubtitleTrack(
        id = lan.hashCode().toLong(),
        lan = lan,
        lanDoc = lan,
        url = url,
        isAiGenerated = false
    )

    private companion object {
        const val ZH_URL = "https://example.invalid/zh.json"
        const val EN_URL = "https://example.invalid/en.json"
    }
}
