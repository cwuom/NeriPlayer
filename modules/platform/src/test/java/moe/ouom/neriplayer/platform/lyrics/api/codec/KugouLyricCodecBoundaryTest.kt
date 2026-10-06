package moe.ouom.neriplayer.platform.lyrics.api.codec

import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipException
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KugouLyricCodecBoundaryTest {

    @Test
    fun `decodeKugouLyricDownload accepts either success marker`() {
        val lyric = base64("[00:01.00]Hello\r[00:02.00]World")

        assertEquals("[00:01.00]Hello\n[00:02.00]World", decodeKugouLyricDownload(downloadBody(200, 7, lyric)))
        assertEquals("[00:01.00]Hello\n[00:02.00]World", decodeKugouLyricDownload(downloadBody(1, 0, lyric)))
        assertNull(decodeKugouLyricDownload(downloadBody(404, 3, lyric)))
    }

    @Test
    fun `decodeKugouLyricDownload rejects missing, corrupt and blank lyric content`() {
        assertNull(decodeKugouLyricDownload(downloadBody(200, 0, content = null)))
        assertNull(decodeKugouLyricDownload(downloadBody(200, 0, content = "@@not-base64@@")))
        assertNull(decodeKugouLyricDownload(downloadBody(200, 0, content = base64("\uFEFF \r\n "))))
    }

    @Test
    fun `decryptKugouKrcPayload rejects headers, corrupt streams and blank lyrics`() {
        assertNull(decryptKugouKrcPayload(ByteArray(4)))
        assertNull(decryptKugouKrcPayload(encryptKrc("  \n ")))
        assertEquals("[0,10]<0,10,0>x", decryptKugouKrcPayload(encryptKrc("[0,10]<0,10,0>x")))

        val corrupt = runCatching { decryptKugouKrcPayload(ByteArray(12) { 0x11 }) }.exceptionOrNull()

        assertTrue(corrupt is ZipException)
        assertNull(decodeKugouKrcDownloadPayload(downloadBody(200, 0, base64(ByteArray(12) { 0x11 }))))
        assertNull(decodeKugouKrcDownloadPayload(downloadBody(200, 0, base64(ByteArray(3)))))
    }

    @Test
    fun `convertKugouKrcToEditableYrc drops malformed lines and word segments`() {
        val lyric = convertKugouKrcToEditableYrc(
            listOf(
                "[language:abc]",
                "plain text",
                "[99999999999999999999,100]<0,1,0>overflow start",
                "[100,99999999999999999999]<0,1,0>overflow duration",
                "[200,300]no word timing",
                "  [1000, 900]<0,300,0>A<99999999999999999999,1,0>B<300,99999999999999999999,0>C<600, 300,-1>D  "
            ).joinToString("\n")
        )

        assertEquals("[1000,900](1000,300,0)A(1600,300,0)D", lyric)
    }

    @Test
    fun `decodeKugouKrcDownloadPayload requires word timed lyrics`() {
        val body = downloadBody(200, 0, base64(encryptKrc("[1000,900]line without words")))

        assertNull(decodeKugouKrcDownloadPayload(body))
    }

    @Test
    fun `translated krc picks the first usable translation block`() {
        val language = languageTag(
            """
            {"content":[
              "not-an-object",
              {"type":0,"lyricContent":[["romaji"]]},
              {"type":1},
              {"type":1,"lyricContent":[["Love"," ","you"],"  plain  ",[" "],["Sixty five"],42]}
            ]}
            """.trimIndent()
        )
        val payload = decodeKrc(
            """
            [1000,900]<0,300,0>A
            [ar:Singer]
            [language:$language]
            [2000,900]<0,300,0>B
            [3000,900]<0,300,0>C
            [65000,900]<0,300,0>D
            [70000,500]
            """.trimIndent()
        )

        assertEquals(
            "[1000,900](1000,300,0)A\n[2000,900](2000,300,0)B\n" +
                "[3000,900](3000,300,0)C\n[65000,900](65000,300,0)D",
            payload?.lyrics
        )
        assertEquals("[00:01.00]Love you\n[00:02.00]plain\n[01:05.00]Sixty five", payload?.translatedLyrics)
    }

    @Test
    fun `translated krc is dropped when the language tag is unusable`() {
        val wordLine = "[1000,900]<0,300,0>A"
        val blankTranslations = languageTag("""{"content":[{"type":1,"lyricContent":[[" "],""]}]}""")
        val emptyContentWithLineBreaks = Base64.getMimeEncoder(4, "\r\n".toByteArray())
            .encodeToString("""{"content":[]}""".toByteArray())
        val missingContent = languageTag("""{"version":1}""")
        val malformedJson = languageTag("not json")

        listOf(
            wordLine,
            "[language:$blankTranslations]\n$wordLine",
            "[language:${emptyContentWithLineBreaks.replace("\r\n", " ")}]\n$wordLine",
            "[language:$missingContent]\n$wordLine",
            "[language:$malformedJson]\n$wordLine"
        ).forEach { krc ->
            val payload = decodeKrc(krc)

            assertEquals("[1000,900](1000,300,0)A", payload?.lyrics)
            assertNull(payload?.translatedLyrics)
        }
    }

    private fun decodeKrc(krc: String) =
        decodeKugouKrcDownloadPayload(downloadBody(200, 0, base64(encryptKrc(krc))))

    private fun languageTag(json: String): String = base64(json)

    private fun downloadBody(status: Int, errorCode: Int, content: String?): String {
        return JSONObject()
            .put("status", status)
            .put("error_code", errorCode)
            .apply { if (content != null) put("content", content) }
            .toString()
    }

    private fun base64(value: String): String = base64(value.toByteArray(Charsets.UTF_8))

    private fun base64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)

    private fun encryptKrc(raw: String): ByteArray {
        val compressed = ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output).use { it.write(raw.toByteArray(Charsets.UTF_8)) }
            output.toByteArray()
        }
        val encrypted = ByteArray(compressed.size + 4)
        compressed.forEachIndexed { index, byte ->
            encrypted[index + 4] = (byte.toInt() xor KRC_KEY[index % KRC_KEY.size].toInt()).toByte()
        }
        return encrypted
    }

    private companion object {
        val KRC_KEY = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69
        )
    }
}
