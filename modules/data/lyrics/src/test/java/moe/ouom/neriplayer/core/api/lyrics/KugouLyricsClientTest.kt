package moe.ouom.neriplayer.core.api.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class KugouLyricsClientTest {

    @Test
    fun `parseKugouSearchResults reads old mobile search fields`() {
        val results = parseKugouSearchResults(
            """
            {
              "status": 1,
              "data": {
                "info": [
                  {
                    "hash": "abc123",
                    "songname": "爱你",
                    "singername": "陈芳语",
                    "album_name": "爱你",
                    "album_audio_id": 42,
                    "duration": 206
                  }
                ]
              }
            }
            """.trimIndent()
        )

        assertEquals(1, results.size)
        assertEquals("42", results.single().id)
        assertEquals("abc123", results.single().hash)
        assertEquals("爱你", results.single().title)
        assertEquals("陈芳语", results.single().artist)
        assertEquals(206_000L, results.single().durationMs)
    }

    @Test
    fun `parseKugouLyricCandidates keeps id access key duration and score`() {
        val candidates = parseKugouLyricCandidates(
            """
            {
              "status": 200,
              "candidates": [
                {
                  "id": "220297734",
                  "accesskey": "key",
                  "duration": 206000,
                  "score": 60
                }
              ]
            }
            """.trimIndent()
        )

        assertEquals(1, candidates.size)
        assertEquals("220297734", candidates.single().id)
        assertEquals("key", candidates.single().accessKey)
        assertEquals(206_000L, candidates.single().durationMs)
        assertEquals(60, candidates.single().score)
    }

    @Test
    fun `decodeKugouLyricDownload decodes base64 lrc text`() {
        val encoded = Base64.getEncoder()
            .encodeToString("\uFEFF[00:00.00]爱你\r\n".toByteArray(Charsets.UTF_8))
        val lyric = decodeKugouLyricDownload(
            """
            {
              "status": 200,
              "error_code": 0,
              "content": "$encoded"
            }
            """.trimIndent()
        )

        assertEquals("[00:00.00]爱你", lyric)
    }

    @Test
    fun `decodeKugouLyricDownload rejects blank content`() {
        val lyric = decodeKugouLyricDownload(
            """
            {
              "status": 200,
              "error_code": 0,
              "content": ""
            }
            """.trimIndent()
        )

        assertTrue(lyric == null)
    }

    @Test
    fun `convertKugouKrcToEditableYrc preserves word timing`() {
        val lyric = convertKugouKrcToEditableYrc(
            "[1000,900]<0,300,0>爱<300,600,0>你"
        )

        assertEquals("[1000,900](1000,300,0)爱(1300,600,0)你", lyric)
        assertTrue(hasEditableLyricWordTiming(lyric))
    }

    @Test
    fun `decodeKugouKrcDownload decodes encrypted krc as word timed yrc`() {
        val encrypted = encryptKugouKrcForTest("[1000,900]<0,300,0>爱<300,600,0>你")
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        val lyric = decodeKugouKrcDownload(
            """
            {
              "status": 200,
              "error_code": 0,
              "content": "$encoded"
            }
            """.trimIndent()
        )

        assertEquals("[1000,900](1000,300,0)爱(1300,600,0)你", lyric)
    }

    @Test
    fun `decodeKugouKrcDownloadPayload extracts translated lyrics from language tag`() {
        val language = Base64.getEncoder().encodeToString(
            """
            {
              "content": [
                {
                  "type": 1,
                  "lyricContent": [
                    ["Love you"],
                    ["Every day"]
                  ]
                }
              ]
            }
            """.trimIndent().toByteArray(Charsets.UTF_8)
        )
        val encrypted = encryptKugouKrcForTest(
            """
            [language:$language]
            [1000,900]<0,300,0>爱<300,600,0>你
            [2500,1000]<0,500,0>每<500,500,0>天
            """.trimIndent()
        )
        val encoded = Base64.getEncoder().encodeToString(encrypted)
        val payload = decodeKugouKrcDownloadPayload(
            """
            {
              "status": 200,
              "error_code": 0,
              "content": "$encoded"
            }
            """.trimIndent()
        )

        assertEquals(
            "[1000,900](1000,300,0)爱(1300,600,0)你\n" +
                "[2500,1000](2500,500,0)每(3000,500,0)天",
            payload?.lyrics
        )
        assertEquals(
            "[00:01.00]Love you\n[00:02.50]Every day",
            payload?.translatedLyrics
        )
    }
}

private fun encryptKugouKrcForTest(raw: String): ByteArray {
    val compressed = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
        output.toByteArray()
    }
    val key = byteArrayOf(
        0x40,
        0x47,
        0x61,
        0x77,
        0x5e,
        0x32,
        0x74,
        0x47,
        0x51,
        0x36,
        0x31,
        0x2d,
        0xce.toByte(),
        0xd2.toByte(),
        0x6e,
        0x69
    )
    val encrypted = ByteArray(compressed.size + 4)
    compressed.forEachIndexed { index, byte ->
        encrypted[index + 4] = (byte.toInt() xor key[index % key.size].toInt()).toByte()
    }
    return encrypted
}
