package moe.ouom.neriplayer.platform.subsonic.api

import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class SubsonicClientRequestUrlTest {
    private val profile = SubsonicProfile(
        id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc791",
        label = "Example",
        baseUrl = "https://music.example.com:4533/navidrome/",
        username = "demo user"
    )

    @Test
    fun `all prototype endpoint names including numeric suffixes are accepted`() {
        val methods = listOf("ping", "getOpenSubsonicExtensions", "getAlbumList2",
            "getAlbum", "search3", "getSong", "stream", "getCoverArt", "getLyricsBySongId")
        methods.forEach { method ->
            val url = SubsonicClient.requestUrl(profile, "example-password", method)
            assertEquals("/navidrome/rest/$method.view", url.encodedPath)
            assertEquals(4533, url.port)
        }
    }

    @Test
    fun `query values round trip and authentication uses salted token`() {
        val password = "example-password"
        val resourceId = "song / + ? & 中文"
        val url = SubsonicClient.requestUrl(profile, password, "getSong", mapOf("id" to resourceId))
        val salt = requireNotNull(url.queryParameter("s"))
        val expected = MessageDigest.getInstance("MD5")
            .digest((password + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(resourceId, url.queryParameter("id"))
        assertEquals(profile.username, url.queryParameter("u"))
        assertEquals(expected, url.queryParameter("t"))
        assertTrue(salt.matches(Regex("[0-9a-f]{32}")))
        assertEquals("1.16.1", url.queryParameter("v"))
        assertEquals("json", url.queryParameter("f"))
        assertFalse(url.toString().contains(password))
        assertFalse(url.queryParameterNames.contains("p"))
    }

    @Test
    fun `invalid method paths are still rejected`() {
        listOf("", "../stream", "getAlbum/2", "search3?x=1", "3search", "search 3").forEach { method ->
            assertThrows(IllegalArgumentException::class.java) {
                SubsonicClient.requestUrl(profile, "example-password", method)
            }
        }
    }
}
