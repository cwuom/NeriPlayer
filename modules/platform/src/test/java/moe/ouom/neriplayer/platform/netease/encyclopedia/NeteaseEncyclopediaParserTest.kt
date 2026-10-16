package moe.ouom.neriplayer.platform.netease.encyclopedia

import moe.ouom.neriplayer.data.model.encyclopedia.EncyclopediaAward
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseEncyclopediaParserTest {

    @Test
    fun `basic block parses genres tags language bpm awards and appearances`() {
        val summary = parseNeteaseEncyclopediaSummary(BASIC_BLOCK_JSON)

        assertEquals(listOf("流行-华语流行"), summary?.genres)
        assertEquals(listOf("思念", "浪漫"), summary?.tags)
        assertEquals("国语", summary?.language)
        assertEquals(61, summary?.bpm)
        assertEquals(
            listOf(EncyclopediaAward(title = "第2届网易云音乐原创盛典", detail = "获 年度十大歌曲")),
            summary?.awards
        )
        assertEquals(listOf("综艺《舞蹈风暴 第一季》插曲"), summary?.appearances)
    }

    @Test
    fun `missing basic block means no encyclopedia content`() {
        val onlyRelatedPlaylist = """
            {"code":200,"data":{"blocks":[{"code":"SONG_PLAY_ABOUT_RELATED_PLAYLIST"}]}}
        """.trimIndent()

        assertNull(parseNeteaseEncyclopediaSummary(onlyRelatedPlaylist))
    }

    @Test
    fun `non success code and missing data return null`() {
        assertNull(parseNeteaseEncyclopediaSummary("""{"code":400,"data":null}"""))
        assertNull(parseNeteaseEncyclopediaSummary("""{"code":200}"""))
    }

    @Test
    fun `invalid json throws so the caller can treat it as a retryable failure`() {
        val failure = runCatching { parseNeteaseEncyclopediaSummary("") }.exceptionOrNull()

        assertTrue(failure != null)
    }

    @Test
    fun `blank and non positive values are dropped instead of invented`() {
        val summary = parseNeteaseEncyclopediaSummary(
            """
            {"code":200,"data":{"blocks":[{"code":"SONG_PLAY_ABOUT_SONG_BASIC","creatives":[
              {"creativeType":"songTag","resources":[{"uiElement":{"mainTitle":{"title":"  "}}}]},
              {"creativeType":"language","uiElement":{"textLinks":[{"text":""}]}},
              {"creativeType":"bpm","uiElement":{"textLinks":[{"text":"0"}]}}
            ]}]}}
            """.trimIndent()
        )

        assertEquals(emptyList<String>(), summary?.genres)
        assertNull(summary?.language)
        assertNull(summary?.bpm)
    }

    @Test
    fun `credits parse every role with its creators`() {
        val roles = parseNeteaseEncyclopediaCredits(
            """
            {"code":200,"data":{"songCreatorsRoleVos":[
              {"roleName":"作词","creatorMetaVOS":[{"artistName":"米果"}]},
              {"roleName":"编曲","creatorMetaVOS":[{"artistName":"  "},{"artistName":"Terence Teo"}]},
              {"roleName":"","creatorMetaVOS":[{"artistName":"ignored"}]},
              {"roleName":"混音","creatorMetaVOS":[{"artistName":"周天澈"}]}
            ]}}
            """.trimIndent()
        )

        assertEquals(
            listOf(
                "作词" to listOf("米果"),
                "编曲" to listOf("Terence Teo"),
                "混音" to listOf("周天澈")
            ),
            roles.map { it.role to it.names }
        )
    }

    @Test
    fun `credits fall back to empty list for unusable responses`() {
        assertTrue(parseNeteaseEncyclopediaCredits("not json").isEmpty())
        assertTrue(parseNeteaseEncyclopediaCredits("""{"code":400,"data":null}""").isEmpty())
        assertTrue(parseNeteaseEncyclopediaCredits("""{"code":200,"data":{}}""").isEmpty())
        assertTrue(
            parseNeteaseEncyclopediaCredits(
                """{"code":200,"data":{"songCreatorsRoleVos":[]}}"""
            ).isEmpty()
        )
    }

    private companion object {
        val BASIC_BLOCK_JSON = """
            {"code":200,"data":{"blocks":[
              {"code":"SONG_PLAY_ABOUT_MUSIC_MEMORY"},
              {"code":"SONG_PLAY_ABOUT_SONG_BASIC","creatives":[
                {"creativeType":"songTag","resources":[{"uiElement":{"mainTitle":{"title":"流行-华语流行"}}}]},
                {"creativeType":"songBizTag","resources":[
                  {"uiElement":{"mainTitle":{"title":"思念"}}},
                  {"uiElement":{"mainTitle":{"title":"浪漫"}}}
                ]},
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
    }
}
