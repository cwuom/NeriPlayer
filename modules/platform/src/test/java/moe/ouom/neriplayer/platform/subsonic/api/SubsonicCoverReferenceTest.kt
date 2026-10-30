package moe.ouom.neriplayer.platform.subsonic.api

import moe.ouom.neriplayer.platform.subsonic.auth.SubsonicProfile
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicCoverReferenceTest {
    private val profile = SubsonicProfile("9e8a8fc4-1e35-4b5a-884d-8bb2423cc791", "Fixture",
        "https://example.test:4533/music/", "fixture-user")
    private val scope = "bf198eaadc62fc6f3d2a45c2180ad6deaa2a222b9147e769450230fcbbaba802"
    private val endpoint = "https://example.test:4533/music/rest/getCoverArt.view?id=cover&size=600"

    @Test fun `real cover endpoint preserves arbitrary cover ids without exporting authentication`() {
        val url = SubsonicCoverReference.create(profile, "cover / + ? & 中文").toHttpUrl()
        assertEquals("example.test", url.host)
        assertEquals(4533, url.port)
        assertEquals("/music/rest/getCoverArt.view", url.encodedPath)
        assertEquals("cover / + ? & 中文", url.queryParameter("id"))
        assertEquals(setOf("id", "size"), url.queryParameterNames)
        assertEquals("600", url.queryParameter("size"))
        assertEquals("neri-cover=v1:$scope", url.fragment)
        assertEquals("", url.username)
        assertEquals("", url.password)
        assertFalse(url.toString().contains(profile.username))
        val parsed = requireNotNull(SubsonicCoverReference.parse(url))
        assertEquals(profile.baseUrl, parsed.baseUrl)
        assertEquals("cover / + ? & 中文", parsed.coverArtId)
        assertTrue(parsed.matches(profile))
    }

    @Test fun `same logical account on another device matches despite different local identity`() {
        val reference = requireNotNull(SubsonicCoverReference.parse("$endpoint#neri-cover=v1:$scope".toHttpUrl()))
        assertTrue(reference.matches(profile.copy(id = "9e8a8fc4-1e35-4b5a-884d-8bb2423cc792",
            label = "Other device", revision = 17)))
        assertTrue(reference.matches(profile.copy(baseUrl = "https://EXAMPLE.test:4533/music", username = " fixture-user ")))
        for (other in listOf(profile.copy(username = "different-user"),
            profile.copy(baseUrl = "https://example.test/music/"),
            profile.copy(baseUrl = "https://example.test:4533/other/"),
            profile.copy(baseUrl = "http://example.test:4533/music/"))) assertFalse(reference.matches(other))
    }

    @Test fun `marked urls must have an exact endpoint and a unique constrained query`() {
        for (raw in listOf(
            "$endpoint&id=other", "$endpoint&size=600", "$endpoint&u=user", "$endpoint&t=token&s=salt",
            "$endpoint&apiKey=key", endpoint.replace("size=600", "size=300"),
            endpoint.replace("id=cover", "id="), endpoint.replace("id=cover&", ""),
            endpoint.replace("getCoverArt.view", "stream.view"),
            endpoint.replace("getCoverArt.view", "%67etCoverArt.view"),
            endpoint.replace("getCoverArt.view", "getCoverArt.view/"),
            endpoint.replace("rest/getCoverArt", "other/getCoverArt"),
            endpoint.replace("https://", "https://user:secret@"))) {
            assertNull(raw, SubsonicCoverReference.parse("$raw#neri-cover=v1:$scope".toHttpUrl()))
        }
    }

    @Test fun `missing malformed or unsupported source markers cannot select a local account`() {
        for (fragment in listOf("", "#cover", "#neri-cover=v2:$scope", "#neri-cover=v1:short",
            "#neri-cover=v1:${scope.uppercase()}", "#neri-cover=v1:$scope:extra")) {
            assertNull(SubsonicCoverReference.parse("$endpoint$fragment".toHttpUrl()))
        }
        assertNotNull(SubsonicCoverReference.parse("$endpoint#neri-cover=v1:$scope".toHttpUrl()))
    }

    @Test fun `invalid base addresses and empty cover ids cannot become portable references`() {
        for (base in listOf("https://user:secret@example.test/music/", "https://example.test/music/?token=secret",
            "https://example.test/music/#fragment", "https://neri-server.invalid/", "ftp://example.test/music/")) {
            assertThrows(IllegalArgumentException::class.java) { SubsonicCoverReference.create(profile.copy(baseUrl = base), "cover") }
        }
        assertThrows(IllegalArgumentException::class.java) { SubsonicCoverReference.create(profile, " ") }
        assertThrows(IllegalArgumentException::class.java) { SubsonicCoverReference.create(profile.copy(username = " "), "cover") }
    }
}
