package moe.ouom.neriplayer.data.local.media

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_16BE
import java.nio.charset.StandardCharsets.UTF_16LE
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class LocalMediaMetadataEncodingTest {
    @Test
    fun `local metadata repairs utf8 text read as latin1`() {
        with(LocalMediaSupport) {
            assertEquals("阿尔法 - vae", "阿尔法 - vae".toByteArray(UTF_8).toString(ISO_8859_1).takeMeaningfulLocalMetadata())
            assertEquals("阿尔法 - vae", "é˜¿å°”æ³• - vae".takeMeaningfulLocalMetadata())
        }
    }

    @Test
    fun `local metadata repairs gbk text read as latin1`() {
        with(LocalMediaSupport) {
            assertEquals("许嵩", "ÐíáÔ".takeMeaningfulLocalMetadata())
        }
    }

    @Test
    fun `local metadata preserves valid unicode and accented artist names`() {
        with(LocalMediaSupport) {
            listOf("许嵩", "夜に駆ける", "소녀시대", "Björk", "Mötley Crüe", "François", "Café", "Sigur Rós", "ÆØÅ", "ÄÖÜß", "éèêë").forEach { value ->
                assertEquals(value, value.takeMeaningfulLocalMetadata())
            }
        }
    }

    @Test
    fun `legacy id3 frames recover utf8 and gbk bytes mislabeled as latin1`() {
        assertEquals("阿尔法 - vae", LocalMediaSupport.decodeId3TextFrame(byteArrayOf(0) + "阿尔法 - vae".toByteArray(UTF_8)))
        assertEquals("许嵩", LocalMediaSupport.decodeId3TextFrame(byteArrayOf(0) + "许嵩".toByteArray(Charset.forName("GBK"))))
    }

    @Test
    fun `container text preserves utf8 before considering legacy encodings`() {
        with(LocalMediaSupport) {
            listOf("你好", "夜に駆ける", "소녀시대", "Mötley Crüe", "歌名\n副标题",
                "Track\u00A0Name", "Track\u00ADName", "Track ½", "Track 🎵").forEach { value ->
                assertEquals(value, value.toByteArray(UTF_8).decodeContainerText())
            }
            listOf("Björk", "Mötley Crüe", "ÄÖÜß", "éèêë").forEach { value ->
                assertEquals(value, value.toByteArray(ISO_8859_1).decodeContainerText())
            }
            assertEquals("许嵩", "许嵩".toByteArray(Charset.forName("GBK")).decodeContainerText())
        }
    }

    @Test
    fun `container text decodes a single gbk character without a long high byte run`() {
        with(LocalMediaSupport) {
            assertEquals("许", byteArrayOf(0xD0.toByte(), 0xED.toByte()).decodeContainerText())
        }
    }

    @Test
    fun `unlabelled container text still ranks a reversible gb18030 candidate beside utf8 symbols`() {
        val ambiguous = byteArrayOf(0xC2.toByte(), 0xA5.toByte())
        assertArrayEquals(ambiguous, "楼".toByteArray(Charset.forName("GB18030")))
        assertArrayEquals(ambiguous, "¥".toByteArray(UTF_8))

        with(LocalMediaSupport) {
            assertEquals("楼", ambiguous.decodeContainerText())
        }
    }

    @Test
    fun `explicit unicode encodings preserve yen when its bytes also form a gbk ideograph`() {
        val yen = "¥"
        assertEquals(yen, LocalMediaSupport.decodeId3TextFrame(byteArrayOf(3) + yen.toByteArray(UTF_8)))
        assertEquals(yen, LocalMediaSupport.decodeId3TextFrame(byteArrayOf(1) + yen.toByteArray(Charsets.UTF_16)))
        with(LocalMediaSupport) {
            assertEquals(yen, (byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + yen.toByteArray(UTF_8)).decodeContainerText())
        }
    }

    @Test
    fun `container text decodes gbk characters separated by ascii`() {
        with(LocalMediaSupport) {
            val bytes = byteArrayOf(0xD0.toByte(), 0xED.toByte(), 0x2D, 0x41, 0x2D, 0xE1.toByte(), 0xD4.toByte())

            assertEquals("许-A-嵩", bytes.decodeContainerText())
        }
    }

    @Test
    fun `container text decodes adjacent gb18030 characters whose latin1 form is uppercase`() {
        with(LocalMediaSupport) {
            val bytes = byteArrayOf(0xD6.toByte(), 0xD0.toByte(), 0xCE.toByte(), 0xC4.toByte())

            assertEquals("中文", bytes.decodeContainerText())
        }
    }

    @Test
    fun `container legacy fallback preserves European names and accented runs`() {
        with(LocalMediaSupport) {
            listOf("Björk", "Mötley Crüe", "François", "ÄÖÜß", "éèêë", "éè").forEach { value ->
                assertEquals(value, value.toByteArray(ISO_8859_1).decodeContainerText())
            }
        }
    }

    @Test
    fun `container fallback keeps UTF16 without a bom when zero bytes identify it`() {
        with(LocalMediaSupport) {
            assertEquals("2024年", "2024年".toByteArray(UTF_16LE).decodeContainerText())
            assertEquals("2024年", "2024年".toByteArray(UTF_16BE).decodeContainerText())
        }
    }
}
