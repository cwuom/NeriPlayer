package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.HttpUrl
import okhttp3.Response
import org.w3c.dom.Element
import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveEntry
import moe.ouom.neriplayer.data.model.sync.transport.WebDavArchiveObjectPolicy

object WebDavArchiveListing {
    private const val MAX_ENTRIES = 100_000
    fun isOwnedPath(path: String): Boolean = WebDavArchiveObjectPolicy.isOwnedPath(path)
    fun isStrongETag(etag: String): Boolean = WebDavArchiveObjectPolicy.isStrongETag(etag)

    internal fun read(response: Response, root: HttpUrl): List<WebDavArchiveEntry> {
        val resources = readResources(response)
        val seen = HashSet<HttpUrl>()
        val entries = ArrayList<WebDavArchiveEntry>()
        for (resource in resources) {
            val url = resourceUrl(resource, root)
            if (!seen.add(url)) throw IOException("Duplicate WebDAV archive listing response")
            if (url == root) requireCollection(resource)
            else readEntry(resource, root, url)?.let(entries::add)
        }
        if (root !in seen) throw IOException("Incomplete WebDAV archive listing")
        return entries
    }

    private fun readResources(response: Response): List<Element> {
        if (response.code != 207) throw WebDavApiException(response.code, "Failed to list WebDAV archive objects")
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val builder = factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") } }
        val document = builder.parse(WebDavXmlResponse.read(response))
        if (document.documentElement.namespaceURI != "DAV:" || document.documentElement.localName != "multistatus") {
            throw IOException("Invalid WebDAV archive listing")
        }
        val resources = children(document.documentElement, "response")
        if (resources.isEmpty() || resources.size > MAX_ENTRIES) throw IOException("Invalid WebDAV archive listing size")
        return resources
    }

    private fun resourceUrl(resource: Element, root: HttpUrl): HttpUrl {
        val href = single(resource, "href").textContent.trim()
        val url = canonicalUrl(href, root)
        requireSameOrigin(url, root)
        return url
    }

    private fun canonicalUrl(href: String, root: HttpUrl): HttpUrl {
        val url = root.resolve(href) ?: throw IOException("Invalid WebDAV archive URL")
        if (href != url.toString() && href != url.encodedPath) throw IOException("Noncanonical WebDAV archive URL")
        return url
    }

    private fun requireSameOrigin(url: HttpUrl, root: HttpUrl) {
        if (url.scheme != root.scheme || url.host != root.host || url.port != root.port) {
            throw IOException("Invalid WebDAV archive listing scope")
        }
    }

    private fun readEntry(resource: Element, root: HttpUrl, url: HttpUrl): WebDavArchiveEntry? {
        val path = directChildPath(root, url)
        if (!isOwnedPath(path)) return null
        val properties = properties(resource)
        if (isCollection(url, properties)) return null
        return WebDavArchiveEntry(path, strongETag(properties))
    }

    private fun directChildPath(root: HttpUrl, url: HttpUrl): String {
        val collection = url.pathSegments.last().isEmpty()
        val path = if (collection) url.pathSegments.dropLast(1).last() else url.pathSegments.last()
        val expected = root.newBuilder().addPathSegment(path).apply { if (collection) addPathSegment("") }.build()
        if (url.query != null || url.fragment != null || url != expected) {
            throw IOException("WebDAV archive listing contains an unexpected path")
        }
        return path
    }

    private fun isCollection(url: HttpUrl, properties: Element): Boolean =
        url.pathSegments.last().isEmpty() || children(single(properties, "resourcetype"), "collection").isNotEmpty()

    private fun strongETag(properties: Element): String {
        val etag = single(properties, "getetag").textContent.trim()
        if (!isStrongETag(etag)) throw IOException("Archive object has no strong ETag")
        return etag
    }

    private fun requireCollection(resource: Element) {
        if (children(single(properties(resource), "resourcetype"), "collection").isEmpty()) throw IOException("Archive root is not a collection")
    }

    private fun properties(resource: Element): Element {
        if (children(resource, "status").isNotEmpty()) throw IOException("Incomplete WebDAV archive listing response")
        val successful = children(resource, "propstat").filter(::successfulStatus)
        if (successful.size != 1) throw IOException("Invalid WebDAV archive listing properties")
        return single(successful.single(), "prop")
    }

    private fun successfulStatus(propstat: Element): Boolean {
        val status = single(propstat, "status").textContent.trim().split(Regex("\\s+")).getOrNull(1) ?: return false
        return status.toIntOrNull() == 200
    }

    private fun single(parent: Element, name: String): Element = children(parent, name).singleOrNull()
        ?: throw IOException("Invalid WebDAV archive listing property")

    private fun children(parent: Element, name: String): List<Element> = (0 until parent.childNodes.length)
        .map { parent.childNodes.item(it) }.filterIsInstance<Element>().filter { it.namespaceURI == "DAV:" && it.localName == name }
}
