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
}
