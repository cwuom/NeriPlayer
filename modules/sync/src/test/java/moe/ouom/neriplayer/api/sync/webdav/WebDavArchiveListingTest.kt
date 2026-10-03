package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class WebDavArchiveListingTest {
    private val root = "https://sync.test/dav/".toHttpUrl()
    private val owned = "neriplayer-sync-v4-${"a".repeat(64)}.zst"

    @Test fun `only canonical root archive files become deletion candidates`() {
        val entries = read(item("/dav/", collection = true) + item("/dav/$owned") +
            item("/dav/music/", collection = true) + item("/dav/notes.txt") + item("/dav/neriplayer-sync-v3.manifest") +
            item("/dav/neriplayer-sync-v4-${"B".repeat(64)}.zst") + item("/dav/neriplayer-sync-v4-${"c".repeat(64)}.zst/", collection = true) +
            item("/dav/neriplayer-sync-v4-${"d".repeat(64)}.zst", collection = true))
        assertEquals(listOf(owned), entries.map { it.path })
        assertEquals("\"etag\"", entries.single().etag)
    }

    @Test fun `foreign nested duplicate and noncanonical responses stop deletion planning`() {
        for (href in listOf("https://other.test/dav/$owned", "/other/$owned", "/dav/sub/$owned", "/dav/../dav/$owned",
            "/dav/$owned?query=1", "/dav/$owned#fragment", "/dav/${owned.replace("a", "%61")}")) {
            assertThrows(IOException::class.java) { read(item("/dav/", collection = true) + item(href)) }
        }
        assertThrows(IOException::class.java) { read(item("/dav/", collection = true) + item("/dav/$owned") + item("/dav/$owned")) }
    }

    @Test fun `absolute URLs must keep origin and remain direct children without query or fragment`() {
        assertEquals(listOf(owned), read(item(root.toString(), collection = true) + item("$root$owned")).map { it.path })
        for (href in listOf("http://sync.test/dav/$owned", "https://sync.test:8443/dav/$owned", "ftp://sync.test/dav/$owned",
            "$root$owned?query=1", "$root$owned#fragment", "https://user@sync.test/dav/$owned", "http://[", owned)) {
            assertThrows(IOException::class.java) { read(item("/dav/", collection = true) + item(href)) }
        }
    }

    @Test fun `missing root invalid status properties and weak validators fail closed`() {
        assertThrows(IOException::class.java) { read(item("/dav/$owned")) }
        for (body in listOf(item("/dav/"), item("/dav/", collection = true) + item("/dav/$owned", etag = "W/\"weak\""),
            item("/dav/", collection = true) + item("/dav/$owned").replace("200 OK", "404 Not Found"),
            item("/dav/", collection = true) + item("/dav/$owned").replace("<d:getetag>\"etag\"</d:getetag>", ""))) {
            assertThrows(IOException::class.java) { read(body) }
        }
    }

    @Test fun `each owned response requires exactly one successful property set and no direct failure status`() {
        val rootEntry = item("/dav/", collection = true)
        val objectEntry = item("/dav/$owned")
        val propstat = objectEntry.substringAfter("</d:href>").substringBefore("</d:response>")
        val malformed = listOf(objectEntry.replace("<d:propstat>", "<d:status>HTTP/1.1 200 OK</d:status><d:propstat>"),
            objectEntry.replace("</d:response>", "$propstat</d:response>"),
            objectEntry.replace(propstat, ""), objectEntry.replace("<d:status>HTTP/1.1 200 OK</d:status>", ""),
            objectEntry.replace("HTTP/1.1 200 OK", "HTTP/1.1"), objectEntry.replace("HTTP/1.1 200 OK", "HTTP/1.1 invalid"))
        for (entry in malformed) assertThrows(IOException::class.java) { read(rootEntry + entry) }
        val extraMissingProperty = propstat.replace("200 OK", "404 Not Found")
        assertEquals(listOf(owned), read(rootEntry + objectEntry.replace("</d:response>", "$extraMissingProperty</d:response>")).map { it.path })
    }

    @Test fun `HTTP errors unknown root XML and DTD cannot become an empty successful listing`() {
        assertThrows(WebDavApiException::class.java) { WebDavArchiveListing.read(response(500, "failure"), root) }
        assertThrows(IOException::class.java) { WebDavArchiveListing.read(response(207, "<bad/>"), root) }
        assertThrows(IOException::class.java) { WebDavArchiveListing.read(response(207,
            "<!DOCTYPE d:multistatus [<!ENTITY x 'test'>]><d:multistatus xmlns:d=\"DAV:\"/>"), root) }
        assertThrows(IOException::class.java) { read("") }
        assertThrows(IOException::class.java) { WebDavArchiveListing.read(response(207, "<d:notMultistatus xmlns:d=\"DAV:\"/>"), root) }
    }

    @Test fun `an oversized response inventory stops before any resource can become a candidate`() {
        assertThrows(IOException::class.java) { read("<d:response/>".repeat(100_001)) }
    }

    private fun read(items: String) = WebDavArchiveListing.read(response(207, "<d:multistatus xmlns:d=\"DAV:\">$items</d:multistatus>"), root)
    private fun item(href: String, collection: Boolean = false, etag: String = "\"etag\""): String =
        "<d:response><d:href>$href</d:href><d:propstat><d:prop><d:resourcetype>${if (collection) "<d:collection/>" else ""}</d:resourcetype>" +
            "<d:getetag>$etag</d:getetag></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
    private fun response(code: Int, text: String): Response = Response.Builder().request(Request.Builder().url(root).build())
        .protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(text.toResponseBody()).build()
}
