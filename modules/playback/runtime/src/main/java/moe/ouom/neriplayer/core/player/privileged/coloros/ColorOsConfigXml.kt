package moe.ouom.neriplayer.core.player.privileged.coloros

import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler

internal object ColorOsConfigXml {
    const val MAX_XML_BYTES = 128 * 1024
    private val packagePattern = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")
    private val numberPattern = Regex("[0-9]{1,10}")

    fun version(xml: String): Long = parse(xml).version

    fun enable(xml: String, applicationId: String, version: Long): String {
        requirePackage(applicationId)
        val parsed = parse(xml)
        checkExclusion(parsed, applicationId)
        val changes = mutableListOf(versionChange(parsed, version))
        val whitelist = parsed.section("whitelist")
        if (parsed.packages(whitelist).none { it.getAttribute("name") == applicationId }) {
            changes += insertion(parsed, whitelist, "<packageName name=\"$applicationId\"/>")
        }
        val policy = parsed.section("policy")
        val own = parsed.packages(policy).singleOrNull { it.getAttribute("name") == applicationId }
        if (own == null) {
            changes += insertion(parsed, policy,
                "<packageName name=\"$applicationId\" actionPriority=\"0\" actionPriorityQs=\"2\" lyricEntrance=\"52\"/>")
        } else if (own.getAttribute("lyricEntrance") != "52") {
            val range = parsed.range(own)
            val tag = xml.substring(range.start, range.openEnd)
            val valueRange = attributeValueRange(tag, "lyricEntrance")
            val replacement = if (valueRange != null) {
                tag.replaceRange(valueRange, "52")
            } else {
                val index = if (tag.endsWith("/>")) tag.length - 2 else tag.length - 1
                tag.substring(0, index) + " lyricEntrance=\"52\"" + tag.substring(index)
            }
            changes += Change(range.start, range.openEnd, replacement)
        }
        return edit(xml, changes).also { parse(it) }
    }

    fun withVersion(xml: String, version: Long): String {
        val parsed = parse(xml)
        return edit(xml, listOf(versionChange(parsed, version)))
    }

    fun isEnabled(xml: String, applicationId: String): Boolean {
        requirePackage(applicationId)
        val parsed = parse(xml)
        checkExclusion(parsed, applicationId)
        return parsed.packages(parsed.section("whitelist")).any { it.getAttribute("name") == applicationId } &&
            parsed.packages(parsed.section("policy")).any {
                it.getAttribute("name") == applicationId && it.getAttribute("lyricEntrance") == "52"
            }
    }

    fun checkedVersion(value: String): Long {
        if (!numberPattern.matches(value)) unsupported("invalid_version")
        return value.toLongOrNull()?.takeIf { it <= Int.MAX_VALUE } ?: unsupported("version_overflow")
    }

    private fun requirePackage(applicationId: String) {
        if (!packagePattern.matches(applicationId)) unsupported("invalid_package")
    }

    private fun checkExclusion(parsed: Parsed, applicationId: String) {
        val excluded = parsed.elements.singleOrNull { it.tagName == "excludeList" } ?: return
        if (parsed.packages(excluded).any { it.getAttribute("name") == applicationId }) unsupported("package_excluded")
    }

    private fun versionChange(parsed: Parsed, version: Long): Change {
        if (version <= parsed.version || version > Int.MAX_VALUE) unsupported("version_overflow")
        val range = parsed.range(parsed.section("version"))
        val content = parsed.xml.substring(range.openEnd, range.closeStart)
        val digits = Regex("[0-9]+").find(content) ?: unsupported("invalid_version")
        return Change(range.openEnd + digits.range.first, range.openEnd + digits.range.last + 1, version.toString())
    }

    private fun insertion(parsed: Parsed, element: Element, value: String): Change {
        val range = parsed.range(element)
        if (range.closeStart == range.start) {
            val tag = parsed.xml.substring(range.start, range.openEnd)
            return Change(range.start, range.openEnd, tag.dropLast(2) + ">$value</${element.tagName}>")
        }
        return Change(range.openEnd, range.openEnd, value)
    }

    private fun edit(xml: String, changes: List<Change>): String =
        changes.sortedByDescending { it.start }.fold(xml) { current, change ->
            current.replaceRange(change.start, change.end, change.value)
        }

    private fun attributeValueRange(tag: String, name: String): IntRange? {
        var index = tag.indexOfFirst { it.isWhitespace() }.takeIf { it >= 0 } ?: return null
        while (index < tag.length) {
            while (index < tag.length && tag[index].isWhitespace()) index++
            if (index >= tag.length || tag[index] == '/' || tag[index] == '>') return null
            val start = index
            while (index < tag.length && !tag[index].isWhitespace() && tag[index] != '=') index++
            val attribute = tag.substring(start, index)
            while (index < tag.length && tag[index].isWhitespace()) index++
            if (tag.getOrNull(index) != '=') unsupported("invalid_attribute")
            index++
            while (index < tag.length && tag[index].isWhitespace()) index++
            val quote = tag.getOrNull(index) ?: unsupported("invalid_attribute")
            if (quote != '\'' && quote != '"') unsupported("invalid_attribute")
            val valueStart = index + 1
            val valueEnd = tag.indexOf(quote, valueStart)
            if (valueEnd < 0) unsupported("invalid_attribute")
            if (attribute == name) return valueStart until valueEnd
            index = valueEnd + 1
        }
        return null
    }

    private fun parse(xml: String): Parsed {
        if (xml.toByteArray(Charsets.UTF_8).size > MAX_XML_BYTES) unsupported("xml_too_large")
        if (xml.contains("<!DOCTYPE", ignoreCase = true) || xml.contains("<!ENTITY", ignoreCase = true)) {
            unsupported("xml_entity_declaration")
        }
        try {
            val ranges = scan(xml)
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
                isExpandEntityReferences = false
            }
            val builder = factory.newDocumentBuilder().apply {
                setEntityResolver { _, _ -> throw SAXException("external_entity") }
                setErrorHandler(object : DefaultHandler() {
                    override fun error(exception: SAXParseException) { throw exception }
                    override fun fatalError(exception: SAXParseException) { throw exception }
                })
            }
            val document = builder.parse(InputSource(StringReader(xml)))
            val nodes = document.getElementsByTagName("*")
            val elements = (0 until nodes.length).map { nodes.item(it) as Element }
            if (elements.size != ranges.size) unsupported("unknown_schema")
            elements.forEachIndexed { index, element ->
                if (element.tagName != ranges[index].name || element.namespaceURI != null ||
                    element.tagName.contains(':') || (0 until element.attributes.length).any {
                        val attribute = element.attributes.item(it)
                        attribute.nodeName == "xmlns" || attribute.nodeName.contains(':')
                    }) unsupported("namespaced_schema")
            }
            val parsed = Parsed(xml, elements, ranges)
            val version = parsed.section("version")
            if (children(version).isNotEmpty() || version.attributes.length != 0) unsupported("invalid_version")
            val versionRange = parsed.range(version)
            parsed.version = checkedVersion(xml.substring(versionRange.openEnd, versionRange.closeStart).trim())
            val whitelist = parsed.section("whitelist")
            val policy = parsed.section("policy")
            if (whitelist.parentNode !== policy.parentNode || version.parentNode !== policy.parentNode) unsupported("unknown_schema")
            val excludes = elements.filter { it.tagName == "excludeList" }
            if (excludes.size > 1) unsupported("duplicate_section")
            (listOf(whitelist, policy) + excludes).forEach { section ->
                if (section.parentNode !== policy.parentNode) unsupported("unknown_schema")
                if (hasUnexpectedText(section)) unsupported("unknown_package_schema")
                val packages = children(section)
                if (packages.any { it.tagName != "packageName" || children(it).isNotEmpty() ||
                        hasUnexpectedText(it) || !packagePattern.matches(it.getAttribute("name")) }) unsupported("unknown_package_schema")
                if (packages.map { it.getAttribute("name") }.distinct().size != packages.size) unsupported("duplicate_package")
            }
            return parsed
        } catch (exception: ColorOsConfigException) {
            throw exception
        } catch (_: Exception) {
            unsupported("invalid_xml")
        }
    }

    private fun children(element: Element): List<Element> = (0 until element.childNodes.length)
        .mapNotNull { element.childNodes.item(it) as? Element }

    private fun hasUnexpectedText(element: Element): Boolean = (0 until element.childNodes.length).any {
        val node = element.childNodes.item(it)
        (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) && !node.textContent.isNullOrBlank()
    }

    private fun scan(xml: String): List<ElementRange> {
        val ranges = mutableListOf<ElementRange>()
        val stack = ArrayDeque<ElementRange>()
        var offset = 0
        while (offset < xml.length) {
            val start = xml.indexOf('<', offset)
            if (start < 0) break
            val special = when {
                xml.startsWith("<!--", start) -> "-->"
                xml.startsWith("<![CDATA[", start) -> "]]>"
                xml.startsWith("<?", start) -> "?>"
                else -> null
            }
            if (special != null) {
                val end = xml.indexOf(special, start + 2)
                if (end < 0) unsupported("invalid_xml")
                offset = end + special.length
                continue
            }
            if (xml.startsWith("<!", start)) unsupported("xml_declaration")
            var end = start + 1
            var quote: Char? = null
            while (end < xml.length) {
                val char = xml[end]
                if (quote == null && char == '>') break
                if (quote == char) quote = null else if (quote == null && (char == '\'' || char == '"')) quote = char
                end++
            }
            if (end >= xml.length) unsupported("invalid_xml")
            val closing = xml.getOrNull(start + 1) == '/'
            val nameStart = start + if (closing) 2 else 1
            val nameEnd = (nameStart until end).firstOrNull { xml[it].isWhitespace() || xml[it] == '/' } ?: end
            val name = xml.substring(nameStart, nameEnd)
            if (closing) {
                val open = stack.removeLastOrNull() ?: unsupported("invalid_xml")
                if (open.name != name) unsupported("invalid_xml")
                open.closeStart = start
            } else {
                val range = ElementRange(name, start, end + 1, start)
                ranges += range
                if (xml[end - 1] != '/') stack.addLast(range)
                if (stack.size > 64 || ranges.size > 8192) unsupported("xml_complexity")
            }
            offset = end + 1
        }
        if (stack.isNotEmpty()) unsupported("invalid_xml")
        return ranges
    }

    private class Parsed(val xml: String, val elements: List<Element>, val ranges: List<ElementRange>) {
        var version: Long = 0
        fun section(name: String): Element = elements.singleOrNull { it.tagName == name }
            ?: unsupported("missing_or_duplicate_$name")
        fun range(element: Element): ElementRange = ranges[elements.indexOf(element)]
        fun packages(section: Element): List<Element> = children(section)
    }

    private data class ElementRange(val name: String, val start: Int, val openEnd: Int, var closeStart: Int)
    private data class Change(val start: Int, val end: Int, val value: String)
    private fun unsupported(detailCode: String): Nothing =
        throw ColorOsConfigException(ColorOsConfigStatus.UNSUPPORTED, detailCode)
}
