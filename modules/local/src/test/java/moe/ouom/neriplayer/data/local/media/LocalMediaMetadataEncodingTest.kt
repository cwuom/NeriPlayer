package moe.ouom.neriplayer.data.local.media

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.charset.StandardCharsets.UTF_8
import org.junit.Assert.assertEquals
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
            listOf("你好", "夜に駆ける", "소녀시대", "Mötley Crüe", "歌名\n副标题").forEach { value ->
                assertEquals(value, value.toByteArray(UTF_8).decodeContainerText())
            }
            listOf("Björk", "Mötley Crüe", "ÄÖÜß", "éèêë").forEach { value ->
                assertEquals(value, value.toByteArray(ISO_8859_1).decodeContainerText())
            }
            assertEquals("许嵩", "许嵩".toByteArray(Charset.forName("GBK")).decodeContainerText())
        }
    }
}
