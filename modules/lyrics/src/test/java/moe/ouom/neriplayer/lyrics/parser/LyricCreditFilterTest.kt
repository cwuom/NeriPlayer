package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricCreditFilterTest {

    @Test
    fun `netease lrc drops leading and trailing credits and keeps lyrics`() {
        val lrc = """
            [00:00.00] 作词 : 林夕
            [00:01.00] 作曲 : 陈小霞
            [00:02.00] 编曲 Arranger：某某
            [00:03.00] 制作人 Producer：某某
            [00:04.00] 混音/母带工程师 Mixing&Mastering Engineer：某某
            [00:05.00] 出品公司：某某文化
            [00:06.00] OP/SP：某某音乐
            [00:07.00]【未经著作权人许可，不得翻唱、翻录或使用】
            [00:15.00]第一句歌词
            [00:20.00]第二句歌词
            [03:30.00]
            [03:31.00]特别鸣谢：某某
            [03:32.00]Produced by 某某
        """.trimIndent()

        val texts = parseNeteaseLrc(lrc).map { it.text }

        assertEquals(listOf("第一句歌词", "第二句歌词"), texts)
    }

    @Test
    fun `title line and credits sharing the first lyric timestamp are removed`() {
        val lyrics = listOf(
            entry("晴天 - 周杰伦 (Jay Chou)", 0L),
            entry("词：周杰伦", 0L),
            entry("曲：周杰伦", 0L),
            entry("故事的小黄花", 15_638L),
            entry("从出生那年就飘着", 18_891L)
        )

        val texts = lyrics.withoutLyricCredits().map { it.text }

        assertEquals(listOf("故事的小黄花", "从出生那年就飘着"), texts)
    }

    @Test
    fun `unknown role sandwiched between known credits is removed`() {
        val lyrics = listOf(
            entry("作词：某某", 0L),
            entry("人声监修：某某", 1_000L),
            entry("作曲：某某", 2_000L),
            entry("正文", 10_000L)
        )

        assertEquals(listOf("正文"), lyrics.withoutLyricCredits().map { it.text })
    }

    @Test
    fun `duet part markers after the credit block are kept`() {
        val lyrics = listOf(
            entry("作词：某某", 0L),
            entry("男：我爱你", 10_000L),
            entry("女：我也爱你", 12_000L),
            entry("合：在一起", 14_000L)
        )

        assertEquals(
            listOf("男：我爱你", "女：我也爱你", "合：在一起"),
            lyrics.withoutLyricCredits().map { it.text }
        )
    }

    @Test
    fun `credit looking lines in the middle of lyrics are kept`() {
        val lyrics = listOf(
            entry("第一句", 1_000L),
            entry("音乐：是我们唯一的语言", 2_000L),
            entry("第三句", 3_000L)
        )

        assertSame(lyrics, lyrics.withoutLyricCredits())
    }

    @Test
    fun `notice lines are removed anywhere`() {
        val lyrics = listOf(
            entry("第一句", 1_000L),
            entry("TME享有本翻译作品的著作权", 2_000L),
            entry("第三句", 3_000L)
        )

        assertEquals(listOf("第一句", "第三句"), lyrics.withoutLyricCredits().map { it.text })
    }

    @Test
    fun `lyrics made only of credits stay unchanged`() {
        val lyrics = listOf(entry("作曲：某某", 0L), entry("编曲：某某", 1_000L), entry("", 2_000L))

        assertSame(lyrics, lyrics.withoutLyricCredits())
    }

    @Test
    fun `empty and clean lyrics are returned as is`() {
        val empty = emptyList<LyricEntry>()
        val clean = listOf(entry("第一句", 1_000L), entry("第二句", 2_000L))

        assertSame(empty, empty.withoutLyricCredits())
        assertSame(clean, clean.withoutLyricCredits())
    }

    @Test
    fun `first real lyric line is kept when no credit follows`() {
        val lyrics = listOf(entry("Hello - it's me", 1_000L), entry("I was wondering", 2_000L))

        assertSame(lyrics, lyrics.withoutLyricCredits())
    }

    @Test
    fun `yrc and ttml parsers drop credit lines`() {
        val yrc = """
            {"t":0,"c":[{"tx":"作词: "},{"tx":"某某"}]}
            [0,1000](0,1000,0)作词：某某
            [1000,1000](1000,1000,0)作曲：某某
            [12580,3470](12580,250,0)难(12830,300,0)以
        """.trimIndent()
        val ttml = """
            <tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00.000" end="00:01.000"><span begin="00:00.000" end="00:01.000">作词：某某</span></p>
            <p begin="00:05.000" end="00:07.000"><span begin="00:05.000" end="00:07.000">真正的歌词</span></p>
            </div></body></tt>
        """.trimIndent()

        assertEquals(listOf("难以"), parseNeteaseYrc(yrc).map { it.text })
        assertEquals(listOf("真正的歌词"), parseTtmlLyrics(ttml).map { it.text })
    }

    @Test
    fun `enhanced lrc drops credit prelude`() {
        val lrc = """
            [00:00.00]<00:00.00>作词：<00:00.50>某某
            [00:05.00]<00:05.00>真<00:05.50>的
        """.trimIndent()

        assertEquals(listOf("真的"), parseNeteaseLyricsAuto(lrc).map { it.text })
        assertEquals(listOf("真的"), parseNeteaseLrc(lrc).map { it.text })
    }

    @Test
    fun `role prefixes accept compound chinese english and korean roles`() {
        listOf(
            "配唱制作人 Vocal Producer", "词 Lyricist", "OP/SP", "B站", "录音棚 Recording Studio",
            "Lyrics by", "작사", "總監製", "QQ群"
        ).forEach { assertTrue(it, isCreditRolePrefix(it)) }
        listOf("男", "爱情", "Love", "by", "12", "曲终人散", "Rap", "制作人🎵").forEach {
            assertFalse(it, isCreditRolePrefix(it))
        }
    }

    @Test
    fun `line classification covers every kind`() {
        assertEquals(LyricCreditLineKind.BLANK, classifyLyricCreditLine("   "))
        assertEquals(LyricCreditLineKind.NOTICE, classifyLyricCreditLine("© 2024 Some Records"))
        assertEquals(LyricCreditLineKind.NOTICE, classifyLyricCreditLine("All Rights Reserved"))
        assertEquals(LyricCreditLineKind.CREDIT, classifyLyricCreditLine("（作词：某某）"))
        assertEquals(LyricCreditLineKind.CREDIT, classifyLyricCreditLine("Mixed by Someone"))
        assertEquals(LyricCreditLineKind.WEAK_CREDIT, classifyLyricCreditLine("Written by the stars"))
        assertEquals(LyricCreditLineKind.WEAK_CREDIT, classifyLyricCreditLine("人声监修：某某"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("Stand by me"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("男：我爱你"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("作词："))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("12:30 的钟声"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("【副歌】"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("【"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("[作词：某某"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("：作词某某"))
        assertEquals(LyricCreditLineKind.LYRIC, classifyLyricCreditLine("作词".repeat(21) + "：某某"))
        assertEquals(LyricCreditLineKind.CREDIT, classifyLyricCreditLine("Lyricist: Someone"))
    }

    @Test
    fun `first line is a title candidate only for title artist form or before two credits`() {
        val weak = LyricCreditLineKind.WEAK_CREDIT
        val credit = LyricCreditLineKind.CREDIT
        val lyric = LyricCreditLineKind.LYRIC

        assertEquals(listOf(weak, lyric), classifyLyricCreditLines(listOf("晴天 - 周杰伦", "正文")))
        assertEquals(
            listOf(weak, credit, credit, lyric),
            classifyLyricCreditLines(listOf("晴天", "作词：某某", "作曲：某某", "正文"))
        )
        assertEquals(listOf(lyric, credit, lyric), classifyLyricCreditLines(listOf("第一句", "作词：某某", "正文")))
        assertEquals(
            listOf(lyric, credit, credit),
            classifyLyricCreditLines(listOf("很长的真实歌词 - ".repeat(5), "作词：某某", "作曲：某某"))
        )
        assertEquals(listOf(credit, lyric), classifyLyricCreditLines(listOf("作词：某某", "标题")))
        assertEquals(emptyList<LyricCreditLineKind>(), classifyLyricCreditLines(emptyList()))
    }

    @Test
    fun `strong credit helper reports credits and notices only`() {
        assertTrue(isLyricCreditOrNoticeLine("作曲 Composer：某某"))
        assertTrue(isLyricCreditOrNoticeLine("以下歌词翻译由文曲大模型提供"))
        assertFalse(isLyricCreditOrNoticeLine("人声监修：某某"))
        assertFalse(isLyricCreditOrNoticeLine("故事的小黄花"))
    }

    @Test
    fun `generic list overload filters arbitrary line types`() {
        val lines = listOf("作词：某某", "正文一", "正文二", "版权所有")

        assertEquals(listOf("正文一", "正文二"), lines.withoutLyricCredits { it })
    }

    private fun entry(text: String, startMs: Long): LyricEntry {
        return LyricEntry(text = text, startTimeMs = startMs, endTimeMs = startMs + 1_000L)
    }
}
