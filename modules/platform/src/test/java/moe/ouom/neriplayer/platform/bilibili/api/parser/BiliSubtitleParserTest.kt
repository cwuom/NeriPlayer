package moe.ouom.neriplayer.platform.bilibili.api.parser

import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleCue
import moe.ouom.neriplayer.data.model.bilibili.subtitle.BiliSubtitleTrack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiliSubtitleParserTest {
    @Test
    fun `tracks normalize the url and mark generated subtitles`() {
        val tracks = parseBiliSubtitleTracks(JSONObject(SUBTITLE_TRACKS))

        assertEquals(listOf("ai-zh", "zh-CN", "en-US"), tracks.map { it.lan })
        assertEquals(
            listOf(
                "https://aisubtitle.hdslb.com/bfs/ai_subtitle/prod/a.json?auth_key=1",
                "https://i0.hdslb.com/bfs/subtitle/zh.json",
                "https://i0.hdslb.com/x.json"
            ),
            tracks.map { it.url }
        )
        assertEquals(listOf(true, false, false), tracks.map { it.isAiGenerated })
        assertEquals("中文（自动生成）", tracks.first().lanDoc)
        assertEquals(1_497_922_385_058_359_296L, tracks.first().id)
    }

    @Test
    fun `tracks without a usable url or outside a subtitle payload are ignored`() {
        assertTrue(parseBiliSubtitleTracks(null).isEmpty())
        assertTrue(parseBiliSubtitleTracks(JSONObject("""{"subtitles":[]}""")).isEmpty())
        assertTrue(
            parseBiliSubtitleTracks(
                JSONObject("""{"subtitles":[{"id":1,"lan":"zh","subtitle_url":""}]}""")
            ).isEmpty()
        )
    }

    @Test
    fun `selection prefers chinese human subtitles then chinese then any`() {
        val aiChinese = track(lan = "ai-zh", isAi = true)
        val humanChinese = track(lan = "zh-CN")
        val english = track(lan = "en-US")

        assertEquals(humanChinese, selectBiliSubtitleTrack(listOf(aiChinese, humanChinese, english)))
        assertEquals(aiChinese, selectBiliSubtitleTrack(listOf(aiChinese, english)))
        assertEquals(english, selectBiliSubtitleTrack(listOf(english, track(lan = "ja"))))
        assertNull(selectBiliSubtitleTrack(emptyList()))
    }

    @Test
    fun `translated track must use another base language`() {
        val primary = track(lan = "zh-CN")
        val sameLanguageAi = track(lan = "ai-zh", isAi = true)
        val english = track(lan = "en-US")
        val japanese = track(lan = "ja")

        assertEquals(
            english,
            selectBiliTranslatedSubtitleTrack(primary, listOf(sameLanguageAi, english, japanese))
        )
        assertNull(selectBiliTranslatedSubtitleTrack(primary, listOf(sameLanguageAi)))
        assertEquals(
            primary,
            selectBiliTranslatedSubtitleTrack(track(lan = "en-US"), listOf(primary))
        )
    }

    @Test
    fun `language detection covers generated and regional chinese tags`() {
        listOf("zh", "zh-CN", "zh-Hans", "ai-zh", "ZH-TW").forEach { lan ->
            assertTrue("$lan 应识别为中文", isChineseBiliSubtitleLanguage(lan))
        }
        listOf("", "en-US", "ja", "ai-en").forEach { lan ->
            assertTrue("$lan 不应识别为中文", !isChineseBiliSubtitleLanguage(lan))
        }
    }

    @Test
    fun `cues convert seconds to milliseconds and drop unusable rows`() {
        val cues = parseBiliSubtitleCues(JSONObject(SUBTITLE_BODY))

        assertEquals(
            listOf(
                BiliSubtitleCue(1_860L, 4_460L, "第一句"),
                BiliSubtitleCue(5_820L, 13_250L, "第二句"),
                BiliSubtitleCue(12_000L, 12_000L, "时间倒序")
            ),
            cues
        )
    }

    @Test
    fun `cue payload without a body parses to nothing`() {
        assertTrue(parseBiliSubtitleCues(JSONObject("""{"lang":"zh"}""")).isEmpty())
    }

    @Test
    fun `lyric entries keep one line per start time and guarantee a visible width`() {
        val entries = toBiliSubtitleLyricEntries(
            listOf(
                BiliSubtitleCue(2_000L, 2_000L, "副歌"),
                BiliSubtitleCue(1_000L, 1_800L, "  主歌  "),
                BiliSubtitleCue(2_000L, 2_000L, "副歌"),
                BiliSubtitleCue(3_000L, 3_400L, "   ")
            )
        )

        assertEquals(listOf("主歌", "副歌"), entries.map { it.text })
        assertEquals(listOf(1_000L, 2_000L), entries.map { it.startTimeMs })
        assertEquals(listOf(1_800L, 2_001L), entries.map { it.endTimeMs })
        assertTrue(toBiliSubtitleLyricEntries(emptyList()).isEmpty())
    }

    private fun track(lan: String, isAi: Boolean = false): BiliSubtitleTrack =
        BiliSubtitleTrack(
            id = lan.hashCode().toLong(),
            lan = lan,
            lanDoc = lan,
            url = "https://example.invalid/$lan.json",
            isAiGenerated = isAi
        )

    private companion object {
        const val SUBTITLE_TRACKS = """
            {"allow_submit":false,"lan":"","lan_doc":"","subtitles":[
              {"id":1497922385058359296,"lan":"ai-zh","lan_doc":"中文（自动生成）",
               "is_lock":false,"subtitle_url":"//aisubtitle.hdslb.com/bfs/ai_subtitle/prod/a.json?auth_key=1",
               "type":1,"id_str":"1497922385058359296","ai_type":0,"ai_status":2},
              {"id":2,"lan":"zh-CN","lan_doc":"中文（简体）","is_lock":false,
               "subtitle_url":"https://i0.hdslb.com/bfs/subtitle/zh.json","type":0},
              {"id":3,"lan":"en-US","lan_doc":"English","is_lock":false,
               "subtitle_url":"http://i0.hdslb.com/x.json","type":0},
              {"id":4,"lan":"ja","lan_doc":"","is_lock":false,"subtitle_url":"","type":0}
            ]}
        """

        const val SUBTITLE_BODY = """
            {"font_size":0.4,"font_color":"#FFFFFF","type":"AIsubtitle","lang":"zh","body":[
              {"from":1.86,"to":4.46,"sid":1,"location":2,"content":"第一句","music":0.0},
              {"from":5.82,"to":13.25,"sid":2,"location":2,"content":"  第二句  ","music":0.0},
              {"from":3.0,"to":3.5,"sid":3,"location":2,"content":"   ","music":0.0},
              {"from":12.0,"to":11.0,"sid":4,"location":2,"content":"时间倒序","music":0.0},
              {"sid":5,"location":2,"content":"缺少时间","music":0.0}
            ]}
        """
    }
}
