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
        val rootEl = doc.documentElement ?: return null
        val urlBaseRaw = findDirectChildTextByLocalName(rootEl, "URLBase")
            .ifEmpty { findFirstTextByLocalName(rootEl, "URLBase") }
        val endpointBaseUrl = resolveValidUrlBase(urlBaseRaw, locationUrl)

        val deviceElements = findAllElementsByLocalName(rootEl, "device")
        val targetDevice = selectTargetRendererDevice(deviceElements) ?: rootEl

        val udn = findDirectChildTextByLocalName(targetDevice, "UDN")
            .ifEmpty { findFirstTextByLocalName(targetDevice, "UDN") }
        val friendlyName = findDirectChildTextByLocalName(targetDevice, "friendlyName")
            .ifEmpty { findFirstTextByLocalName(targetDevice, "friendlyName") }
        val manufacturer = findDirectChildTextByLocalName(targetDevice, "manufacturer")
            .ifEmpty { findFirstTextByLocalName(targetDevice, "manufacturer") }
        val modelName = findDirectChildTextByLocalName(targetDevice, "modelName")
            .ifEmpty { findFirstTextByLocalName(targetDevice, "modelName") }

        val services = extractDirectServices(targetDevice, endpointBaseUrl).ifEmpty {
            extractSubtreeServices(targetDevice, endpointBaseUrl)
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

    private fun selectTargetRendererDevice(devices: List<Element>): Element? {
        if (devices.isEmpty()) return null
        // 1. Prefer device declaring MediaRenderer deviceType AND its own AVTransport service
        devices.firstOrNull { dev ->
            val deviceType = findDirectChildTextByLocalName(dev, "deviceType")
            deviceType.contains("MediaRenderer", ignoreCase = true) && hasDirectAvTransport(dev)
        }?.let { return it }
        // 2. Any device whose own serviceList includes AVTransport
        devices.firstOrNull { dev -> hasDirectAvTransport(dev) }?.let { return it }
        // 3. Any device declaring MediaRenderer deviceType
        devices.firstOrNull { dev ->
            findDirectChildTextByLocalName(dev, "deviceType")
                .contains("MediaRenderer", ignoreCase = true)
        }?.let { return it }
        return devices.first()
    }

    private fun hasDirectAvTransport(deviceEl: Element): Boolean {
        val serviceList = findDirectChildElementByLocalName(deviceEl, "serviceList") ?: return false
        for (serviceEl in findDirectChildElementsByLocalName(serviceList, "service")) {
            val st = findDirectChildTextByLocalName(serviceEl, "serviceType")
            if (st.equals(SERVICE_AV_TRANSPORT, ignoreCase = true) ||
                st.contains(":AVTransport:", ignoreCase = true)
            ) {
                return true
            }
        }
        return false
    }

    private fun extractDirectServices(deviceEl: Element, endpointBaseUrl: String): List<ServiceEndpoint> {
        val serviceList = findDirectChildElementByLocalName(deviceEl, "serviceList") ?: return emptyList()
        val services = mutableListOf<ServiceEndpoint>()
        for (serviceNode in findDirectChildElementsByLocalName(serviceList, "service")) {
            val serviceType = findDirectChildTextByLocalName(serviceNode, "serviceType")
            val controlPath = findDirectChildTextByLocalName(serviceNode, "controlURL")
            val eventSubPath = findDirectChildTextByLocalName(serviceNode, "eventSubURL")
            if (serviceType.isNotEmpty() && controlPath.isNotEmpty()) {
                services += ServiceEndpoint(
                    serviceType = serviceType,
                    controlUrl = resolveUrl(endpointBaseUrl, controlPath),
                    eventSubUrl = if (eventSubPath.isNotEmpty()) resolveUrl(endpointBaseUrl, eventSubPath) else ""
                )
            }
        }
        return services
    }

    private fun extractSubtreeServices(scopeEl: Element, endpointBaseUrl: String): List<ServiceEndpoint> {
        val services = mutableListOf<ServiceEndpoint>()
        for (node in findAllElementsByLocalName(scopeEl, "service")) {
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
        return services
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
        val instanceElements = findAllElementsByLocalName(root, "InstanceID")
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
        if (trimmed.isEmpty() ||
            trimmed.equals("NOT_IMPLEMENTED", ignoreCase = true) ||
            trimmed.equals("NONE", ignoreCase = true) ||
            trimmed.startsWith("--")
        ) {
            return null
        }
        val isNegative = trimmed.startsWith('-')
        val unsigned = when {
            trimmed.startsWith('-') || trimmed.startsWith('+') -> trimmed.substring(1).trim()
            else -> trimmed
        }
        val parts = unsigned.split(':')
        if (parts.size !in 2..3) return null
        return runCatching {
            val hours: Long
            val minutes: Long
            val secondsPart: String
            if (parts.size == 3) {
                hours = parts[0].toLong()
                minutes = parts[1].toLong()
                secondsPart = parts[2]
                if (hours < 0L || minutes !in 0L..59L) return null
            } else {
                hours = 0L
                minutes = parts[0].toLong()
                secondsPart = parts[1]
                if (minutes < 0L) return null
            }
            val secNormalized = secondsPart.replace(',', '.')
            val secSplit = secNormalized.split('.')
            val seconds = secSplit[0].toLong()
            if (seconds < 0L || (parts.size == 3 && seconds > 59L) || (parts.size == 2 && seconds > 59L)) {
                return null
            }
            val fracPart = secSplit.getOrNull(1)
            val millis = if (fracPart.isNullOrEmpty()) {
                0L
            } else {
                if (!fracPart.all { it.isDigit() }) return null
                fracPart.padEnd(3, '0').take(3).toLong()
            }
            if (isNegative) {
                0L
            } else {
                ((hours * 3600L + minutes * 60L + seconds) * 1000L + millis).coerceAtLeast(0L)
            }
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

    private fun localNameOf(node: Node): String =
        node.localName ?: node.nodeName.substringAfter(':')

    private fun findDirectChildElementsByLocalName(parent: Element, localName: String): List<Element> {
        val children = parent.childNodes
        val list = mutableListOf<Element>()
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            if (localNameOf(child) == localName) {
                list += child
            }
        }
        return list
    }

    private fun findDirectChildElementByLocalName(parent: Element, localName: String): Element? =
        findDirectChildElementsByLocalName(parent, localName).firstOrNull()

    private fun findDirectChildTextByLocalName(parent: Element, localName: String): String =
        findDirectChildElementByLocalName(parent, localName)?.textContent?.trim().orEmpty()

    private fun findAllElementsByLocalName(root: Element, localName: String): List<Element> {
        val nsNodes = root.getElementsByTagNameNS("*", localName)
        if (nsNodes.length > 0) {
            return buildList(nsNodes.length) {
                for (i in 0 until nsNodes.length) {
                    (nsNodes.item(i) as? Element)?.let(::add)
                }
            }
        }
        val all = root.getElementsByTagName("*")
        return buildList {
            for (i in 0 until all.length) {
                val el = all.item(i) as? Element ?: continue
                if (localNameOf(el) == localName) add(el)
            }
        }
    }

    private fun findFirstElementByLocalName(root: Element?, localName: String): Element? {
        if (root == null) return null
        return findAllElementsByLocalName(root, localName).firstOrNull()
    }

    private fun findFirstTextByLocalName(root: Element?, localName: String): String {
        return findFirstElementByLocalName(root, localName)?.textContent?.trim().orEmpty()
    }
}
