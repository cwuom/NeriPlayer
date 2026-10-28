package moe.ouom.neriplayer.core.player.metadata

import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.lyrics.parser.matchTranslationsToLineIndices
import moe.ouom.neriplayer.platform.bilibili.api.client.BiliClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mockito
import org.mockito.stubbing.Answer

class BiliSubtitleLyricsAlignmentTest {
    @Before
    fun clearCachesBeforeTest() {
        PlayerLyricsProvider.clearLyricsCaches()
    }

    @After
    fun clearCachesAfterTest() {
        PlayerLyricsProvider.clearLyricsCaches()
    }

    @Test
    fun `bilibili subtitle provider aligns a translation across two original cues`() = runTest {
        val client = Mockito.mock(BiliClient::class.java, Answer { invocation ->
            when (invocation.method.name) {
                "hasLoginSession" -> true
                "getPlayerSubtitlePayload" -> subtitleTracksPayload()
                "getSubtitlePayload" -> when (invocation.getArgument<String>(0)) {
                    EN_URL -> JSONObject(
                        """{"body":[
                            {"from":54.0,"to":55.6,"content":"and there will almost certainly"},
                            {"from":55.6,"to":56.8,"content":"be many more to come."}
                        ]}"""
                    )
                    ZH_URL -> JSONObject(
                        """{"body":[
                            {"from":54.0,"to":56.7,"content":"而且将来肯定会有更多的模型出现。"}
                        ]}"""
                    )
                    else -> JSONObject()
                }
                else -> Answers.RETURNS_DEFAULTS.answer(invocation)
            }
        })
        val song = SongItem(
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

        val result = PlayerLyricsProvider.loadBiliSubtitleLyrics(
            song = song,
            biliClient = client,
            options = BiliSubtitleLyricsOptions(preferredLanguage = "en-US")
        )
        val matched = matchTranslationsToLineIndices(result.lyrics, result.translatedLyrics)

        assertEquals(
            listOf("and there will almost certainly", "be many more to come."),
            result.lyrics.map { it.text }
        )
        assertEquals("而且将来肯定会有更多的模型出现。", matched[0]?.text)
        assertEquals("而且将来肯定会有更多的模型出现。", matched[1]?.text)
        assertEquals(listOf(54_000L, 55_600L), result.translatedLyrics.map { it.startTimeMs })
    }

    private fun subtitleTracksPayload(): JSONObject = JSONObject().put(
        "subtitles",
        JSONArray().put(
            JSONObject().put("id", 1L).put("lan", "zh-CN").put("lan_doc", "zh-CN")
                .put("subtitle_url", ZH_URL).put("type", 0)
        ).put(
            JSONObject().put("id", 2L).put("lan", "en-US").put("lan_doc", "en-US")
                .put("subtitle_url", EN_URL).put("type", 0)
        )
    )

    private companion object {
        const val ZH_URL = "https://example.invalid/zh.json"
        const val EN_URL = "https://example.invalid/en.json"
    }
}
