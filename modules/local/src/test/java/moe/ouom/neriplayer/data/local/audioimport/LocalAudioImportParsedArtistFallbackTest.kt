package moe.ouom.neriplayer.data.local.audioimport

import moe.ouom.neriplayer.data.model.download.naming.ParsedManagedDownloadFileName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalAudioImportParsedArtistFallbackTest {
    private val parsed = ParsedManagedDownloadFileName(title = "Song", artist = "Parsed Artist", source = "NetEase  Cloud")

    @Test
    fun `missing or blank parsed artists never replace the current artist`() {
        assertNull(fallback(current = "", parsed = null))
        assertNull(fallback(current = "", parsed = ParsedManagedDownloadFileName(artist = null)))
        assertNull(fallback(current = "", parsed = ParsedManagedDownloadFileName(artist = "  ")))
    }

    @Test
    fun `parsed artists replace blank, source-named or fallback artists`() {
        assertEquals("Parsed Artist", fallback(current = null))
        assertEquals("Parsed Artist", fallback(current = "   "))
        assertEquals("Parsed Artist", fallback(current = " netease\tcloud "))
        assertEquals("Parsed Artist", fallback(current = "UNKNOWN  artist"))
    }

    @Test
    fun `real current artists are kept`() {
        assertNull(fallback(current = "Someone Else"))
    }

    private fun fallback(current: String?, parsed: ParsedManagedDownloadFileName? = this.parsed) =
        LocalAudioImportManager.resolveParsedArtistFallback(
            currentArtist = current,
            fallbackArtist = "Unknown Artist",
            parsed = parsed
        )
}
