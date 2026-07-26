package moe.ouom.neriplayer.core.api.search

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QQMusicLyricResponseTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun decodesAResponseThatOmitsBothLyricFields() {
        // 没有歌词时接口连字段都不下发, 这里过去会抛 MissingFieldException
        val decoded = json.decodeFromString<QQMusicLyricResponse>(
            """{"retcode":0,"code":0,"subcode":0}"""
        )

        assertNull(decoded.lyric)
        assertNull(decoded.trans)
    }

    @Test
    fun decodesAResponseThatOnlyCarriesTheOriginalLyric() {
        val decoded = json.decodeFromString<QQMusicLyricResponse>(
            """{"retcode":0,"lyric":"[00:00.00]hello"}"""
        )

        assertEquals("[00:00.00]hello", decoded.lyric)
        assertNull(decoded.trans)
    }

    @Test
    fun decodesAResponseCarryingBothLyricAndTranslation() {
        val decoded = json.decodeFromString<QQMusicLyricResponse>(
            """{"lyric":"[00:00.00]hello","trans":"[00:00.00]你好"}"""
        )

        assertEquals("[00:00.00]hello", decoded.lyric)
        assertEquals("[00:00.00]你好", decoded.trans)
    }

    @Test
    fun keepsExplicitNullsAsNull() {
        val decoded = json.decodeFromString<QQMusicLyricResponse>(
            """{"lyric":null,"trans":null}"""
        )

        assertNull(decoded.lyric)
        assertNull(decoded.trans)
    }

    @Test
    fun decodesTheGetPlayLyricInfoEnvelope() {
        val decoded = json.decodeFromString<QQMusicLyricContainer>(
            """{"req":{"code":0,"data":{"lyric":"bHlyaWM=","trans":"dHJhbnM=","songID":247294300}}}"""
        )

        assertEquals("bHlyaWM=", decoded.req?.data?.lyric)
        assertEquals("dHJhbnM=", decoded.req?.data?.trans)
    }

    @Test
    fun toleratesAnEnvelopeWithoutData() {
        val decoded = json.decodeFromString<QQMusicLyricContainer>(
            """{"req":{"code":-1}}"""
        )

        assertNull(decoded.req?.data)
    }

    @Test
    fun dropsThePlaceholderLinesQqUsesForUntranslatedLyrics() {
        val stripped = stripUntranslatedPlaceholderLines(
            """
            [ti:夜に駆ける]
            [00:00.00]//
            [00:01.80]像是沉溺溶化一般
            [00:09.20]//
            [00:31.58]你只留下了一句「再见了」
            """.trimIndent()
        )

        assertEquals(
            """
            [ti:夜に駆ける]
            [00:01.80]像是沉溺溶化一般
            [00:31.58]你只留下了一句「再见了」
            """.trimIndent(),
            stripped
        )
    }

    @Test
    fun keepsTranslationLinesThatMerelyContainSlashes() {
        val stripped = stripUntranslatedPlaceholderLines("[00:01.00]AC//DC 现场")

        assertEquals("[00:01.00]AC//DC 现场", stripped)
    }

    @Test
    fun returnsNullWhenEveryTranslationLineIsAPlaceholder() {
        val stripped = stripUntranslatedPlaceholderLines(
            "[00:00.00]//\n[00:01.00]//"
        )

        assertNull(stripped)
    }

    @Test
    fun returnsNullForMissingTranslation() {
        assertNull(stripUntranslatedPlaceholderLines(null))
        assertNull(stripUntranslatedPlaceholderLines("   "))
    }
}
