package moe.ouom.neriplayer.lyrics.parser

import moe.ouom.neriplayer.data.model.lyrics.LyricEntry
import moe.ouom.neriplayer.data.model.lyrics.WordTiming
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BilingualLyricDisplayTest {
    @Test
    fun `same timestamp lines share one display interval without changing source lyrics`() {
        val source = listOf(
            line("Once I was seven years old my momma told me", 20_500L, 20_500L),
            line("那年我七岁 妈妈就对我说", 20_500L, 25_000L),
            line("Next line", 25_000L, 30_000L)
        )

        val displayed = prepareBilingualLyricsForDisplay(source)

        assertEquals(2, displayed.size)
        assertEquals("Once I was seven years old my momma told me\n那年我七岁 妈妈就对我说", displayed[0].text)
        assertEquals(20_500L, displayed[0].startTimeMs)
        assertEquals(25_000L, displayed[0].endTimeMs)
        assertEquals(0, findCurrentLineIndex(displayed, 24_999L))
        assertEquals(1, findCurrentLineIndex(displayed, 25_000L))
        assertEquals(3, source.size)
        assertEquals("那年我七岁 妈妈就对我说", source[1].text)
        assertSame(source[2], displayed[1])
    }

    @Test
    fun `same timestamp grouping keeps every line and the longest interval`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("first", 1_000L, 5_000L), line("second", 1_000L, 4_000L), line("third", 1_000L, 3_000L))
        )

        assertEquals(listOf(LyricEntry("first\nsecond\nthird", 1_000L, 5_000L)), displayed)
    }

    @Test
    fun `same timestamp grouping also separates a mixed sentence within a source row`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("Hello world 你好世界", 1_000L, 1_000L), line("Another sentence", 1_000L, 4_000L))
        )

        assertEquals(listOf(LyricEntry("Hello world\n你好世界\nAnother sentence", 1_000L, 4_000L)), displayed)
    }

    @Test
    fun `translation offset of thirty milliseconds follows the original for the whole sentence`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("Once I was seven years old", 20_500L, 20_530L), line("那年我七岁", 20_530L, 25_000L))
        )

        assertEquals(listOf(LyricEntry("Once I was seven years old\n那年我七岁", 20_500L, 25_000L)), displayed)
    }

    @Test
    fun `nearby grouping includes fifty milliseconds but leaves a later translation separate`() {
        val source = listOf(
            line("First sentence", 1_000L, 1_050L), line("第一句", 1_050L, 3_000L),
            line("Second sentence", 3_000L, 3_051L), line("第二句", 3_051L, 5_000L)
        )

        val displayed = prepareBilingualLyricsForDisplay(source)

        assertEquals(listOf("First sentence\n第一句", "Second sentence", "第二句"), displayed.map { it.text })
        assertSame(source[2], displayed[1])
        assertSame(source[3], displayed[2])
    }

    @Test
    fun `nearby groups remain anchored at the first timestamp`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("First sentence", 1_000L, 1_030L), line("第一句", 1_030L, 1_060L), line("Next sentence", 1_060L, 4_000L))
        )

        assertEquals(listOf("First sentence\n第一句", "Next sentence"), displayed.map { it.text })
        assertEquals(1_060L, displayed[1].startTimeMs)
    }

    @Test
    fun `a timestamp group adjacent to a nearby translation preserves every source row`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("First voice", 1_000L, 1_000L), line("Second voice", 1_000L, 1_030L), line("合唱译文", 1_030L, 4_000L))
        )

        assertEquals(listOf("First voice\nSecond voice\n合唱译文"), displayed.map { it.text })
    }

    @Test
    fun `nearby lines with the same language keep their own timing`() {
        val source = listOf(line("first", 1_000L, 1_030L), line("second", 1_030L, 4_000L))

        assertSame(source, prepareBilingualLyricsForDisplay(source))
    }

    @Test
    fun `nearby kana and hangul lines are not inferred to be chinese translations`() {
        val source = listOf(
            line("English", 1_000L, 1_030L), line("日本語の歌", 1_030L, 2_000L),
            line("Another sentence", 2_000L, 2_030L), line("한국어 가사", 2_030L, 4_000L)
        )

        assertSame(source, prepareBilingualLyricsForDisplay(source))
    }

    @Test
    fun `an english sentence followed by chinese starts a new display line`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("She said where'd you wanna go 你说你想去哪", 1_000L, 4_000L))
        )

        assertEquals(listOf(LyricEntry("She said where'd you wanna go\n你说你想去哪", 1_000L, 4_000L)), displayed)
    }

    @Test
    fun `mixed line splitting preserves accents punctuation and timing`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("Déjà vu, my friend! 似曾相识，我的朋友！", 1_000L, 4_000L))
        )

        assertEquals("Déjà vu, my friend!\n似曾相识，我的朋友！", displayed.single().text)
        assertEquals(1_000L, displayed.single().startTimeMs)
        assertEquals(4_000L, displayed.single().endTimeMs)
    }

    @Test
    fun `parentheses stay with the second language in either order`() {
        val cases = listOf(
            "I love you (我爱你)" to "I love you\n(我爱你)",
            "你好 (Hello)" to "你好\n(Hello)",
            "Hello( 你好 )" to "Hello\n( 你好 )"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `nested opening punctuation and its inner spaces stay with the second language`() {
        val cases = listOf(
            "Hello ([你好])" to "Hello\n([你好])",
            "Hello （“ 你好 ”）" to "Hello\n（“ 你好 ”）",
            "你好 （[ Hello ]）" to "你好\n（[ Hello ]）"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `opening unicode quotes stay with the second language`() {
        val cases = listOf(
            "Hello “你好”" to "Hello\n“你好”",
            "Hello “你好" to "Hello\n“你好",
            "你好 「Hello」" to "你好\n「Hello」"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `paired ascii quotes stay with their second language text`() {
        val cases = listOf(
            "Hello \"你好\"" to "Hello\n\"你好\"",
            "\"Hello\" \"你好\"" to "\"Hello\"\n\"你好\"",
            "Hello \"（ 你好 ）\"" to "Hello\n\"（ 你好 ）\""
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `first language closing quotes and sentence punctuation stay on the first line`() {
        val cases = listOf(
            "\"Hello!\" 你好" to "\"Hello!\"\n你好",
            "“Hello!” 你好" to "“Hello!”\n你好",
            "Hello \"friend\" 你好" to "Hello \"friend\"\n你好",
            "(Hello) 你好" to "(Hello)\n你好",
            "Hello \" 你好" to "Hello \"\n你好",
            "Hello?! “你好”" to "Hello?!\n“你好”"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `unicode low quote pairs keep their closing quote on the first line`() {
        val cases = listOf(
            "„Hello“ 你好" to "„Hello“\n你好",
            "‚Hello‘ 你好" to "‚Hello‘\n你好",
            "„Hello” 你好" to "„Hello”\n你好"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `reverse guillemet pairs keep their closing quote on the first line`() {
        val cases = listOf(
            "»Hello« 你好" to "»Hello«\n你好",
            "›Hello‹ 你好" to "›Hello‹\n你好",
            "«Hello« 你好" to "«Hello«\n你好"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `an earlier complete quote pair does not consume the next language opening quote`() {
        val cases = listOf(
            "„Hello“ „你好“" to "„Hello“\n„你好“",
            "‚Hello‘ ‚你好‘" to "‚Hello‘\n‚你好‘",
            "«Hello» «你好»" to "«Hello»\n«你好»",
            "»Hello« »你好«" to "»Hello«\n»你好«",
            "›Hello‹ ›你好‹" to "›Hello‹\n›你好‹"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `latin apostrophes do not change the following single quote pair`() {
        val cases = listOf(
            "I’m here ‘你好’" to "I’m here\n‘你好’",
            "James’ ‘你好’" to "James’\n‘你好’",
            "Rock’n’roll ‘摇滚’" to "Rock’n’roll\n‘摇滚’",
            "‚I’m here‘ 你好" to "‚I’m here‘\n你好"
        )

        for ((source, expected) in cases) {
            assertEquals(source, expected, prepareBilingualLyricsForDisplay(listOf(line(source))).single().text)
        }
    }

    @Test
    fun `timestamp grouping also keeps translated parentheses on their own line`() {
        val displayed = prepareBilingualLyricsForDisplay(listOf(line("Hello (你好)"), line("Another voice")))

        assertEquals("Hello\n(你好)\nAnother voice", displayed.single().text)
    }

    @Test
    fun `mixed line splitting preserves supplementary han characters`() {
        val displayed = prepareBilingualLyricsForDisplay(listOf(line("Hello world 𠀀𠮷")))

        assertEquals("Hello world\n𠀀𠮷", displayed.single().text)
    }

    @Test
    fun `nearby grouping recognizes supplementary han characters`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("Hello world", 1_000L, 1_030L), line("𠀀𠮷", 1_030L, 4_000L))
        )

        assertEquals(listOf(LyricEntry("Hello world\n𠀀𠮷", 1_000L, 4_000L)), displayed)
    }

    @Test
    fun `supplementary letters from another script prevent bilingual inference`() {
        val mixed = listOf(line("Hello 𐐀 你好"))
        val nearby = listOf(line("Hello world", 1_000L, 1_030L), line("𐐀你好", 1_030L, 4_000L))

        assertSame(mixed, prepareBilingualLyricsForDisplay(mixed))
        assertSame(nearby, prepareBilingualLyricsForDisplay(nearby))
    }

    @Test
    fun `mixed line splitting preserves combining accents before the language boundary`() {
        val displayed = prepareBilingualLyricsForDisplay(listOf(line("Cafe\u0301 你好")))

        assertEquals("Cafe\u0301\n你好", displayed.single().text)
    }

    @Test
    fun `mixed line splitting also handles a chinese sentence followed by english`() {
        val displayed = prepareBilingualLyricsForDisplay(listOf(line("你说你想去哪 Where do you wanna go?")))

        assertEquals("你说你想去哪\nWhere do you wanna go?", displayed.single().text)
    }

    @Test
    fun `mixed lines without a separating space can still start a new display line`() {
        val displayed = prepareBilingualLyricsForDisplay(listOf(line("Hello world你好世界")))

        assertEquals("Hello world\n你好世界", displayed.single().text)
    }

    @Test
    fun `multilingual phrases and japanese text keep their intentional layout`() {
        for (text in listOf("我爱 you 每一天", "English 中文 English", "Hello 世界の歌", "Hello 世界 한국어", "你好 Привет", "English\n中文")) {
            val source = listOf(line(text))

            assertSame(text, source, prepareBilingualLyricsForDisplay(source))
        }
    }

    @Test
    fun `plain lyrics and empty lists preserve their list identity`() {
        val empty = emptyList<LyricEntry>()
        val source = listOf(line("English lyrics, 123!"), line("中文歌词，123！", 5_000L, 8_000L), line("123", 9_000L, 10_000L))

        assertSame(empty, prepareBilingualLyricsForDisplay(empty))
        assertSame(source, prepareBilingualLyricsForDisplay(source))
    }

    @Test
    fun `word timed lines retain their text character counts and identity`() {
        val timed = line("Hello 你好").copy(words = listOf(WordTiming(1_000L, 4_000L, 8)))
        val following = line("原有译文", 1_000L, 4_000L)
        val source = listOf(timed, following)

        assertSame(source, prepareBilingualLyricsForDisplay(source))
        assertSame(timed, source[0])
        assertEquals(listOf(WordTiming(1_000L, 4_000L, 8)), source[0].words)
    }

    @Test
    fun `empty word timing lists are ordinary lines`() {
        val displayed = prepareBilingualLyricsForDisplay(
            listOf(line("Hello world").copy(words = emptyList()), line("你好世界"))
        )

        assertEquals("Hello world\n你好世界", displayed.single().text)
    }

    @Test
    fun `grouping preserves an explicit translation on the first source row`() {
        val source = listOf(line("Hello", 1_000L, 1_000L).copy(translation = "已有译文"), line("你好"))

        val displayed = prepareBilingualLyricsForDisplay(source)

        assertEquals(listOf(LyricEntry("Hello\n你好", 1_000L, 4_000L, translation = "已有译文")), displayed)
        assertEquals("已有译文", source[0].translation)
    }

    @Test
    fun `a following explicit translation cannot be lost during grouping`() {
        val source = listOf(line("Hello"), line("你好").copy(translation = "Existing translation"))

        assertEquals(
            listOf(LyricEntry("Hello\n你好", 1_000L, 4_000L, translation = "Existing translation")),
            prepareBilingualLyricsForDisplay(source)
        )
    }

    @Test
    fun `an explicitly cleared translation is preserved during grouping`() {
        val source = listOf(line("Hello").copy(translation = ""), line("你好"))

        assertEquals(
            listOf(LyricEntry("Hello\n你好", 1_000L, 4_000L, translation = "")),
            prepareBilingualLyricsForDisplay(source)
        )
    }

    @Test
    fun `grouping preserves translations from every source row in their original order`() {
        val source = listOf(
            line("Hello").copy(translation = "你好"),
            line("World").copy(translation = "世界")
        )

        assertEquals(
            listOf(LyricEntry("Hello\nWorld", 1_000L, 4_000L, translation = "你好\n世界")),
            prepareBilingualLyricsForDisplay(source)
        )
    }

    @Test
    fun `blank translation rows do not add empty lines to nonblank translations`() {
        val source = listOf(
            line("Hello").copy(translation = ""),
            line("World").copy(translation = "世界"),
            line("Again").copy(translation = "  ")
        )

        assertEquals(
            listOf(LyricEntry("Hello\nWorld\nAgain", 1_000L, 4_000L, translation = "世界")),
            prepareBilingualLyricsForDisplay(source)
        )
    }

    @Test
    fun `metadata lines keep their text and remain separate from lyrics at the same timestamp`() {
        val source = listOf(line("OP：唯迹文化"), line("这是正文"))

        assertSame(source, prepareBilingualLyricsForDisplay(source))
    }

    @Test
    fun `metadata lines remain barriers between nearby bilingual lyrics`() {
        val source = listOf(
            line("Hello world", 1_000L, 1_000L),
            line("作词：A songwriter", 1_000L, 1_030L),
            line("你好世界", 1_030L, 4_000L)
        )

        assertSame(source, prepareBilingualLyricsForDisplay(source))
    }

    @Test
    fun `splitting a mixed line retains its explicit translation`() {
        val displayed = prepareBilingualLyricsForDisplay(listOf(line("Hello world 你好世界").copy(translation = "Existing translation")))

        assertEquals("Hello world\n你好世界", displayed.single().text)
        assertEquals("Existing translation", displayed.single().translation)
    }

    @Test
    fun `display groups retain their original source index after preceding metadata`() {
        val source = listOf(line("OP：唯迹文化"), line("Hello world"), line("你好世界"))

        val displayed = prepareBilingualLyricDisplay(source)

        assertEquals(listOf("OP：唯迹文化", "Hello world\n你好世界"), displayed.lyrics.map { it.text })
        assertEquals(listOf(0, 1), displayed.sourceIndices)
    }

    @Test
    fun `word timing separates display groups with distinct source indices at the same timestamp`() {
        val timed = line("Singing").copy(words = listOf(WordTiming(1_000L, 4_000L, 7)))
        val source = listOf(line("First verse"), line("第一句"), timed, line("Second verse"), line("第二句"))

        val displayed = prepareBilingualLyricDisplay(source)

        assertEquals(listOf("First verse\n第一句", "Singing", "Second verse\n第二句"), displayed.lyrics.map { it.text })
        assertEquals(listOf(0, 2, 3), displayed.sourceIndices)
        assertSame(timed, displayed.lyrics[1])
    }

    @Test
    fun `splitting inline bilingual text keeps the exact source index`() {
        val source = listOf(line("Earlier verse", 0L, 1_000L), line("Hello world 你好世界"), line("Later verse", 5_000L, 8_000L))

        val displayed = prepareBilingualLyricDisplay(source)

        assertEquals(listOf("Earlier verse", "Hello world\n你好世界", "Later verse"), displayed.lyrics.map { it.text })
        assertEquals(listOf(0, 1, 2), displayed.sourceIndices)
    }

    private fun line(text: String, startMs: Long = 1_000L, endMs: Long = 4_000L): LyricEntry =
        LyricEntry(text, startMs, endMs)
}
