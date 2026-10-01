package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import javax.xml.parsers.DocumentBuilderFactory
import okhttp3.Response
import org.w3c.dom.Element

internal object WebDavDirectoryResponse {
    private const val DAV_NAMESPACE = "DAV:"

    fun statusCode(response: Response): Int {
        if (response.code != 207) {
            if (response.isSuccessful) throw IOException("Invalid WebDAV directory response: expected multi-status")
            return response.code
        }
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") }
        }
        val document = builder.parse(WebDavXmlResponse.read(response))
        val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
        if (responses.length != 1) throw IOException("Invalid WebDAV directory response")
        val resource = responses.item(0) as Element
        val directStatus = resource.childrenNamed("status").firstOrNull()
        if (directStatus != null) {
            val status = parseStatus(directStatus)
            if (status in 200..299) throw IOException("Invalid WebDAV directory response: missing resource type")
            return status
        }
        return propertyStatus(resource)
    }

    private fun propertyStatus(resource: Element): Int {
        val property = resourceTypeProperty(resource)
        val status = propertyCode(property)
        // 属性缺失不能当成资源缺失，否则会错误提示目录不存在
        if (status == 404) throw WebDavApiException(status, "Failed to check WebDAV directory properties: $status")
        if (status in 200..299) requireCollection(property)
        return status
    }

    private fun resourceTypeProperty(resource: Element): Element {
        val properties = resource.childrenNamed("propstat")
        val resourceTypes = properties.filter { resourceType(it) != null }
        return resourceTypes.firstOrNull { propertyCode(it) in 200..299 }
            ?: resourceTypes.ifEmpty { properties }.firstOrNull()
            ?: throw IOException("Invalid WebDAV directory response: missing properties")
    }

    private fun requireCollection(property: Element) {
        val type = resourceType(property)
            ?: throw IOException("Invalid WebDAV directory response: missing resource type")
        if (type.childrenNamed("collection").isEmpty()) throw IOException("WebDAV sync path is not a directory")
    }

    private fun resourceType(property: Element): Element? = property.childrenNamed("prop").firstOrNull()
        ?.childrenNamed("resourcetype")?.firstOrNull()

    private fun propertyCode(property: Element): Int = parseStatus(
        property.childrenNamed("status").firstOrNull()
            ?: throw IOException("Invalid WebDAV directory response: missing status")
    )

    private fun Element.childrenNamed(name: String): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }.filterIsInstance<Element>()
        .filter { it.namespaceURI == DAV_NAMESPACE && it.localName == name }

    private fun parseStatus(element: Element): Int = element.textContent.trim()
        .split(Regex("\\s+"))
        .getOrNull(1)?.toIntOrNull()
        ?: throw IOException("Invalid WebDAV directory response: malformed status")
}
