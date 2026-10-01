package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import java.nio.charset.Charset
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavDirectoryResponseTest {
    private val remoteUrl = "https://example.test/dav/sync/neriplayer-sync.json"

    @Test
    fun `UTF8 collection response is accepted`() {
        assertEquals(200, parse(collectionXml().toByteArray()))
    }

    @Test
    fun `UTF16 response with BOM remains readable`() {
        assertEquals(200, parse(collectionXml("UTF-16").toByteArray(Charsets.UTF_16)))
    }

    @Test
    fun `UTF16 declarations without BOM remain readable`() {
        for (encoding in listOf("UTF-16LE", "UTF-16BE")) {
            assertEquals(200, parse(collectionXml(encoding).toByteArray(Charset.forName(encoding))))
        }
    }

    @Test
    fun `XML declaration selects a non UTF8 response encoding`() {
        val xml = collectionXml("ISO-8859-1").replace("d:", "caf\u00e9:").replace("xmlns:d=", "xmlns:caf\u00e9=")

        assertEquals(200, parse(xml.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `HTTP charset takes precedence over the XML declaration without BOM`() {
        val xml = collectionXml("UTF-8").replace("d:", "caf\u00e9:").replace("xmlns:d=", "xmlns:caf\u00e9=")

        assertEquals(200, parse(xml.toByteArray(Charsets.ISO_8859_1), "application/xml; charset=ISO-8859-1"))
    }

    @Test
    fun `BOM takes precedence over a conflicting HTTP charset`() {
        val xml = collectionXml("UTF-16", "caf\u00e9")

        assertEquals(200, parse(xml.toByteArray(Charsets.UTF_16), "application/xml; charset=ISO-8859-1"))
    }

    @Test
    fun `DTD remains forbidden in UTF16 and declared encodings`() {
        for (encoding in listOf("UTF-8", "UTF-16", "UTF-16LE", "UTF-16BE", "ISO-8859-1")) {
            val xml = "<?xml version=\"1.0\" encoding=\"$encoding\"?>" +
                "<!DOCTYPE d:multistatus [<!ENTITY label \"unsafe\">]>" +
                collectionXml(displayName = "&label;").substringAfter("?>")
            val error = assertThrows(IOException::class.java) {
                parse(xml.toByteArray(Charset.forName(encoding)))
            }

            assertTrue(error.message!!.contains("DTD"))
        }
    }

    @Test
    fun `external document types are rejected before entity resolution`() {
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>" +
            "<!DOCTYPE d:multistatus SYSTEM \"https://example.test/private.dtd\">" +
            collectionXml().substringAfter("?>")
        val error = assertThrows(IOException::class.java) {
            parse(xml.toByteArray(Charsets.UTF_16))
        }

        assertTrue(error.message!!.contains("DTD"))
    }

    @Test
    fun `directory probe explicitly requests resource type`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            assertEquals("PROPFIND", request.method)
            assertEquals("0", request.header("Depth"))
            assertEquals("Basic test", request.header("Authorization"))
            assertNotNull(request.body)
            val body = Buffer().apply { request.body!!.writeTo(this) }.readByteArray()
            val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
                .newDocumentBuilder().parse(body.inputStream())
            assertEquals(1, document.getElementsByTagNameNS("DAV:", "resourcetype").length)
            response(collectionXml().toByteArray(), request = request)
        }.build()

        probe(client).requireExists(remoteUrl)
    }

    @Test
    fun `ordinary HTTP success cannot establish a WebDAV directory`() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response("<html>ok</html>".toByteArray(), code = 200, request = chain.request())
        }.build()

        assertThrows(IOException::class.java) { probe(client).requireExists(remoteUrl) }
    }

    @Test
    fun `regular WebDAV file cannot establish a directory`() {
        val xml = collectionXml().replace("<d:collection/>", "")

        assertThrows(IOException::class.java) { parse(xml.toByteArray()) }
    }

    @Test
    fun `successful unrelated properties do not establish a directory`() {
        val xml = multistatus("""
            <d:propstat><d:prop><d:displayname>sync</d:displayname></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop>
            <d:status>HTTP/1.1 404 Not Found</d:status></d:propstat>
        """.trimIndent())

        val error = assertThrows(WebDavApiException::class.java) { parse(xml.toByteArray()) }
        assertEquals(404, error.statusCode)
    }

    @Test
    fun `collection in a failed property response cannot establish a directory`() {
        val xml = collectionXml().replace("HTTP/1.1 200 OK", "HTTP/1.1 403 Forbidden")

        assertEquals(403, parse(xml.toByteArray()))
    }

    @Test
    fun `collection must be in the DAV namespace and resource type property`() {
        for (property in listOf(
            "<d:resourcetype><collection xmlns=\"other\"/></d:resourcetype>",
            "<d:displayname><d:collection/></d:displayname>"
        )) {
            val xml = multistatus("<d:propstat><d:prop>$property</d:prop>" +
                "<d:status>HTTP/1.1 200 OK</d:status></d:propstat>")

            assertThrows(IOException::class.java) { parse(xml.toByteArray()) }
        }
    }

    @Test
    fun `resource success without a successful collection property is rejected`() {
        val xml = multistatus("<d:status>HTTP/1.1 200 OK</d:status>")

        assertThrows(IOException::class.java) { parse(xml.toByteArray()) }
    }

    @Test
    fun `property not found remains distinct from a missing directory`() {
        val xml = collectionXml().replace("HTTP/1.1 200 OK", "HTTP/1.1 404 Not Found")
        val error = assertThrows(WebDavApiException::class.java) { parse(xml.toByteArray()) }

        assertEquals(404, error.statusCode)
    }

    @Test
    fun `resource authentication permission and missing statuses remain distinguishable`() {
        for (status in listOf(401, 403, 404)) {
            val xml = multistatus("<d:status>HTTP/1.1 $status Failure</d:status>")

            assertEquals(status, parse(xml.toByteArray()))
        }
    }

    private fun parse(bytes: ByteArray, contentType: String = "application/xml"): Int =
        response(bytes, contentType).use(WebDavDirectoryResponse::statusCode)

    private fun response(
        bytes: ByteArray,
        contentType: String = "application/xml",
        code: Int = 207,
        request: Request = Request.Builder().url(remoteUrl).build()
    ): Response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(code).message("test response").body(bytes.toResponseBody(contentType.toMediaType())).build()

    private fun probe(client: OkHttpClient) = WebDavDirectoryProbe(
        client, "Basic test", "authentication failed", "directory missing", "access denied"
    )

    private fun collectionXml(encoding: String = "UTF-8", displayName: String = "sync"): String =
        "<?xml version=\"1.0\" encoding=\"$encoding\"?>" + multistatus("""
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype>
            <d:displayname>$displayName</d:displayname></d:prop>
            <d:status>HTTP/1.1 200 OK</d:status></d:propstat>
        """.trimIndent())

    private fun multistatus(content: String): String =
        "<d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/dav/sync/</d:href>" +
            content + "</d:response></d:multistatus>"
}
