package moe.ouom.neriplayer.api.sync.webdav

import java.io.IOException
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import moe.ouom.neriplayer.api.sync.http.SyncResponseBodyReader
import okhttp3.Response
import org.w3c.dom.Element
import org.xml.sax.InputSource

internal object WebDavDirectoryResponse {
    private const val DAV_NAMESPACE = "DAV:"

    fun statusCode(response: Response): Int {
        if (response.code != 207) return response.code
        val xml = SyncResponseBodyReader.readText(response.body).removePrefix("\uFEFF")
        // 目录响应不需要 DTD，解析前拒绝它以阻止实体展开和外部资源读取
        if (xml.contains("<!DOCTYPE", ignoreCase = true)) throw IOException("Invalid WebDAV directory response: DTD is not allowed")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
        }
        val builder = factory.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw IOException("External XML entities are not allowed") }
        }
        val document = builder.parse(InputSource(StringReader(xml)))
        val responses = document.getElementsByTagNameNS(DAV_NAMESPACE, "response")
        if (responses.length != 1) throw IOException("Invalid WebDAV directory response")
        val resource = responses.item(0) as Element
        val children = (0 until resource.childNodes.length).map { resource.childNodes.item(it) }.filterIsInstance<Element>()
        val directStatus = children.firstOrNull { it.namespaceURI == DAV_NAMESPACE && it.localName == "status" }
        if (directStatus != null) return parseStatus(directStatus)
        return propertyStatus(resource)
    }

    private fun propertyStatus(resource: Element): Int {
        val statuses = resource.getElementsByTagNameNS(DAV_NAMESPACE, "status")
        val codes = (0 until statuses.length).map { parseStatus(statuses.item(it) as Element) }
        val status = codes.firstOrNull { it in 200..299 } ?: codes.firstOrNull()
            ?: throw IOException("Invalid WebDAV directory response: missing status")
        // 属性缺失不能当成资源缺失，否则会错误提示目录不存在
        if (status == 404) throw WebDavApiException(status, "Failed to check WebDAV directory properties: $status")
        return status
    }

    private fun parseStatus(element: Element): Int = element.textContent.trim()
        .split(Regex("\\s+"))
        .getOrNull(1)?.toIntOrNull()
        ?: throw IOException("Invalid WebDAV directory response: malformed status")
}
