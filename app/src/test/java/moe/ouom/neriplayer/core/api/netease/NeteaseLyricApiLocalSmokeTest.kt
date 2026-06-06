package moe.ouom.neriplayer.core.api.netease

import java.io.File
import moe.ouom.neriplayer.core.player.metadata.extractPreferredNeteaseLyricContent
import moe.ouom.neriplayer.data.auth.common.parseRawCookieText
import moe.ouom.neriplayer.data.auth.netease.validateAndSanitizeNeteaseCookies
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class NeteaseLyricApiLocalSmokeTest {

    @Test
    fun `local cookie can fetch lyric payload`() {
        val smokeEnabled = System.getProperty("runNeteaseSmoke") == "true"
        assumeTrue("Local smoke test disabled. Pass -DrunNeteaseSmoke=true to enable.", smokeEnabled)

        val cookieFile = listOf(
            File(".ck/netease-cookie.txt"),
            File("../.ck/netease-cookie.txt"),
            File("E:/AndroidProject/NeriPlayer/.ck/netease-cookie.txt")
        ).firstOrNull(File::exists)
        assumeTrue("NetEase cookie file not found.", cookieFile != null)

        val cookies = parseRawCookieText(cookieFile!!.readText())
        val validation = validateAndSanitizeNeteaseCookies(cookies)
        assumeTrue("NetEase cookie missing login token.", validation.isAccepted)

        val client = NeteaseClient()
        client.setPersistedCookies(validation.sanitizedCookies)

        val payload = client.getLyricNew(songId = 33894312L)
        val preferredLyric = extractPreferredNeteaseLyricContent(payload)

        assertTrue("Expected yrc or lrc lyric content from local smoke request.", preferredLyric.isNotBlank())
    }
}
