package moe.ouom.neriplayer.core.player.dlna.protocol

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import java.net.URI
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Hardened UPnP/SOAP XML parser and SOAP envelope builder.
 * Disables DTDs and external entities to prevent XXE on untrusted LAN devices.
 */
object UpnpXmlParser {

    const val SERVICE_AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val SERVICE_RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val SERVICE_CONNECTION_MANAGER = "urn:schemas-upnp-org:service:ConnectionManager:1"

    data class ServiceEndpoint(
        val serviceType: String,
        val controlUrl: String,
        val eventSubUrl: String = ""
    )

    data class ParsedDeviceDescription(
        val location: String,
        val udn: String,
        val friendlyName: String,
        val manufacturer: String,
        val modelName: String,
        val services: List<ServiceEndpoint>
    )

    sealed class SoapParseResult {
        data class Success(val values: Map<String, String>) : SoapParseResult()
        data class Fault(val errorCode: Int, val errorDescription: String) : SoapParseResult()
        data class Invalid(val reason: String) : SoapParseResult()
    }

    fun resolveUrl(baseUrl: String, relativeOrAbsolute: String): String {
        val trimmed = relativeOrAbsolute.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            return trimmed
        }
        return runCatching {
            URI(baseUrl).resolve(trimmed).toString()
        }.getOrElse {
            val schemeEnd = baseUrl.indexOf("://")
            if (schemeEnd < 0) return trimmed
            val pathStart = baseUrl.indexOf('/', schemeEnd + 3)
            val root = if (pathStart < 0) baseUrl else baseUrl.substring(0, pathStart)
            if (trimmed.startsWith("/")) {
                root + trimmed
            } else {
                val baseDir = baseUrl.substringBeforeLast('/', root)
                "$baseDir/$trimmed"
            }
        }
    }

    fun parseDeviceDescription(xml: String, locationUrl: String): ParsedDeviceDescription? {
        val doc = parseDocument(xml) ?: return null
        val udn = findFirstTextByLocalName(doc.documentElement, "UDN")
        val friendlyName = findFirstTextByLocalName(doc.documentElement, "friendlyName")
        val manufacturer = findFirstTextByLocalName(doc.documentElement, "manufacturer")
        val modelName = findFirstTextByLocalName(doc.documentElement, "modelName")
        val urlBaseRaw = findFirstTextByLocalName(doc.documentElement, "URLBase")
        val endpointBaseUrl = resolveValidUrlBase(urlBaseRaw, locationUrl)

        val services = mutableListOf<ServiceEndpoint>()
        val serviceNodes = doc.getElementsByTagNameNS("*", "service")
        val fallbackNodes = if (serviceNodes.length == 0) doc.getElementsByTagName("service") else serviceNodes
        for (i in 0 until fallbackNodes.length) {
            val node = fallbackNodes.item(i) as? Element ?: continue
            val serviceType = findFirstTextByLocalName(node, "serviceType")
            val controlPath = findFirstTextByLocalName(node, "controlURL")
            val eventSubPath = findFirstTextByLocalName(node, "eventSubURL")
            if (serviceType.isNotEmpty() && controlPath.isNotEmpty()) {
                services += ServiceEndpoint(
                    serviceType = serviceType,
                    controlUrl = resolveUrl(endpointBaseUrl, controlPath),
                    eventSubUrl = if (eventSubPath.isNotEmpty()) resolveUrl(endpointBaseUrl, eventSubPath) else ""
                )
            }
        }
        if (udn.isEmpty() && friendlyName.isEmpty()) return null
        return ParsedDeviceDescription(
            location = locationUrl,
            udn = udn.ifEmpty { locationUrl },
            friendlyName = friendlyName.ifEmpty { udn.ifEmpty { locationUrl } },
            manufacturer = manufacturer,
            modelName = modelName,
            services = services
        )
    }

    fun buildSoapEnvelope(serviceType: String, action: String, actionBodyXml: String): String {
        val escapedService = DidlLiteBuilder.escapeXml(serviceType)
        val escapedAction = DidlLiteBuilder.escapeXml(action)
        return buildString(actionBodyXml.length + 320) {
            append("""<?xml version="1.0" encoding="utf-8"?>""")
            append("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" """)
            append("""s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""")
            append("""<s:Body><u:$escapedAction xmlns:u="$escapedService">""")
            append(actionBodyXml)
            append("""</u:$escapedAction></s:Body></s:Envelope>""")
        }
    }

    fun parseSoapResponse(xml: String, expectedAction: String): SoapParseResult {
        val doc = parseDocument(xml) ?: return SoapParseResult.Invalid("Malformed SOAP XML")
        val faultCode = findFirstTextByLocalName(doc.documentElement, "errorCode")
        if (faultCode.isNotEmpty()) {
            val codeInt = faultCode.toIntOrNull() ?: -1
            val desc = findFirstTextByLocalName(doc.documentElement, "errorDescription")
            return SoapParseResult.Fault(codeInt, desc)
        }
        val expectedName = "${expectedAction}Response"
        val responseEl = findFirstElementByLocalName(doc.documentElement, expectedName)
            ?: return SoapParseResult.Invalid("Missing $expectedName")

        val values = LinkedHashMap<String, String>()
        val children = responseEl.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i)
            if (child.nodeType != Node.ELEMENT_NODE) continue
            val name = child.localName ?: child.nodeName.substringAfter(':')
            values[name] = child.textContent?.trim().orEmpty()
        }
        return SoapParseResult.Success(values)
    }

    fun parseGenaLastChange(xml: String): Map<String, String> {
        val doc = parseDocument(xml) ?: return emptyMap()
        val lastChangeText = findFirstTextByLocalName(doc.documentElement, "LastChange")
        val eventDoc = if (lastChangeText.isNotEmpty()) parseDocument(lastChangeText) else doc
        val root = eventDoc?.documentElement ?: return emptyMap()
        val result = LinkedHashMap<String, String>()
        val instanceNodes = root.getElementsByTagNameNS("*", "InstanceID")
        val instanceElements = mutableListOf<Element>()
        for (i in 0 until instanceNodes.length) {
            (instanceNodes.item(i) as? Element)?.let { instanceElements += it }
        }
        if (instanceElements.isEmpty()) {
            val fallbackNodes = root.getElementsByTagName("InstanceID")
            for (i in 0 until fallbackNodes.length) {
                (fallbackNodes.item(i) as? Element)?.let { instanceElements += it }
            }
        }
        val scopes: List<Element> = if (instanceElements.isNotEmpty()) {
            instanceElements.filter { it.getAttribute("val").trim().let { id -> id.isEmpty() || id == "0" } }
        } else {
            listOf(root)
        }
        for (scope in scopes) {
            val all = scope.getElementsByTagName("*")
            for (i in 0 until all.length) {
                val el = all.item(i) as? Element ?: continue
                val name = el.localName ?: el.nodeName.substringAfter(':')
                if (name == "InstanceID") continue
                if (name == "Volume" || name == "Mute") {
                    val channel = el.getAttribute("channel").trim()
                    if (channel.isNotEmpty() && !channel.equals("Master", ignoreCase = true)) {
                        continue
                    }
                }
                val value = el.getAttribute("val").trim()
                if (value.isNotEmpty()) {
                    result[name] = value
                }
            }
        }
        return result
    }

    private fun resolveValidUrlBase(urlBaseRaw: String, locationUrl: String): String {
        val trimmed = urlBaseRaw.trim()
        if (trimmed.isEmpty()) return locationUrl
        return runCatching {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
                trimmed
            } else {
                locationUrl
            }
        }.getOrDefault(locationUrl)
    }

    fun parseUpnpTimeToMs(text: String): Long? {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.equals("NOT_IMPLEMENTED", ignoreCase = true)) return null
        val parts = trimmed.split(':')
        if (parts.size !in 2..3) return null
        return runCatching {
            val hours: Long
            val minutes: Long
            val secondsPart: String
            if (parts.size == 3) {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                secondsPart = parts[2]
            } else {
                hours = 0L
                minutes = parts[0].toLong()
                secondsPart = parts[1]
            }
            val secSplit = secondsPart.split('.')
            val seconds = secSplit[0].toLong()
            val millis = secSplit.getOrNull(1)?.padEnd(3, '0')?.take(3)?.toLongOrNull() ?: 0L
            ((hours * 3600L + minutes * 60L + seconds) * 1000L + millis).coerceAtLeast(0L)
        }.getOrNull()
    }

    fun formatUpnpTime(positionMs: Long): String {
        val clampedSeconds = (positionMs.coerceAtLeast(0L)) / 1000L
        val hours = clampedSeconds / 3600L
        val minutes = (clampedSeconds / 60L) % 60L
        val seconds = clampedSeconds % 60L
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun parseDocument(xml: String): org.w3c.dom.Document? {
        if (xml.isBlank()) return null
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                runCatching { setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true) }
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
                runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
                runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
                isExpandEntityReferences = false
            }
            factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
        }.getOrNull()
    }

    private fun findFirstElementByLocalName(root: Element?, localName: String): Element? {
        if (root == null) return null
        val nodes = root.getElementsByTagNameNS("*", localName)
        if (nodes.length > 0) return nodes.item(0) as? Element
        val all = root.getElementsByTagName("*")
        for (i in 0 until all.length) {
            val el = all.item(i) as? Element ?: continue
            val candidate = el.localName ?: el.nodeName.substringAfter(':')
            if (candidate == localName) return el
        }
        return null
    }

    private fun findFirstTextByLocalName(root: Element?, localName: String): String {
        return findFirstElementByLocalName(root, localName)?.textContent?.trim().orEmpty()
    }
}
