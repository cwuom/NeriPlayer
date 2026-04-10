package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
import org.json.JSONArray
import org.json.JSONObject

/**
 * 统一处理 YouTube / YouTube Music 首页 bootstrap HTML
 * 优先从 ytcfg.set({...}) 提取配置，再回退到正则匹配，避免被页面格式细节卡死
 */
internal class YouTubeBootstrapHtmlSource(html: String) {
    private val rawHtml = html

    private val normalizedHtml by lazy(LazyThreadSafetyMode.NONE) {
        decodeInlineJavascriptEscapes(rawHtml)
    }

    private val ytcfg by lazy(LazyThreadSafetyMode.NONE) {
        extractYtcfgJson(rawHtml)
            ?: normalizedHtml
                .takeUnless { it == rawHtml }
                ?.let(::extractYtcfgJson)
    }

    fun requireString(
        errorPrefix: String,
        primaryField: String,
        vararg fallbackFields: String
    ): String {
        return optionalString(primaryField, *fallbackFields).ifBlank {
            throw IOException("$errorPrefix: $primaryField")
        }
    }

    fun optionalString(primaryField: String, vararg fallbackFields: String): String {
        return findValue(
            fieldNames = arrayOf(primaryField, *fallbackFields),
            patternBuilder = ::stringFieldPattern
        )
    }

    fun optionalNumber(primaryField: String, vararg fallbackFields: String): String {
        return findValue(
            fieldNames = arrayOf(primaryField, *fallbackFields),
            patternBuilder = ::numberFieldPattern
        )
    }

    fun optionalBoolean(primaryField: String, vararg fallbackFields: String): String {
        return findValue(
            fieldNames = arrayOf(primaryField, *fallbackFields),
            patternBuilder = ::booleanFieldPattern
        )
    }

    private fun findValue(
        fieldNames: Array<String>,
        patternBuilder: (String) -> String
    ): String {
        fieldNames.asSequence()
            .map { fieldName -> ytcfg.findScalarDeep(fieldName) }
            .firstOrNull { it.isNotBlank() }
            ?.let { return it }

        findRegexValue(rawHtml, fieldNames, patternBuilder)
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        return normalizedHtml
            .takeUnless { it == rawHtml }
            ?.let { decoded ->
                findRegexValue(decoded, fieldNames, patternBuilder)
            }
            .orEmpty()
    }

    private fun extractYtcfgJson(source: String): JSONObject? {
        var searchStart = 0
        while (true) {
            val match = ytcfgSetCallPattern.find(source, startIndex = searchStart)
            if (match == null) {
                return null
            }
            val callIndex = match.range.first
            val objectStart = source.indexOf('{', startIndex = callIndex)
            if (objectStart < 0) {
                searchStart = match.range.last + 1
                continue
            }
            val objectEnd = findMatchingBrace(source, objectStart)
            if (objectEnd == null) {
                searchStart = objectStart + 1
                continue
            }
            val candidate = source.substring(objectStart, objectEnd + 1)
            val parsed = parseYtcfgCandidate(candidate)
            if (
                parsed != null &&
                (
                    parsed.has("INNERTUBE_API_KEY") ||
                        parsed.has("INNERTUBE_CLIENT_VERSION") ||
                        parsed.has("VISITOR_DATA") ||
                        parsed.has("WEB_PLAYER_CONTEXT_CONFIGS")
                    )
            ) {
                return parsed
            }
            searchStart = objectEnd + 1
        }
    }

    private fun findMatchingBrace(source: String, objectStart: Int): Int? {
        var depth = 0
        var quoteChar = '\u0000'
        var escaped = false
        for (index in objectStart until source.length) {
            val ch = source[index]
            if (quoteChar != '\u0000') {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == quoteChar -> quoteChar = '\u0000'
                }
                continue
            }
            when (ch) {
                '"', '\'' -> quoteChar = ch
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) {
                        return index
                    }
                }
            }
        }
        return null
    }

    private fun findRegexValue(
        source: String,
        fieldNames: Array<String>,
        patternBuilder: (String) -> String
    ): String {
        return fieldNames.asSequence()
            .map { fieldName ->
                Regex(patternBuilder(fieldName))
                    .find(source)
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()
            }
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
    }

    private fun parseYtcfgCandidate(candidate: String): JSONObject? {
        runCatching { JSONObject(candidate) }.getOrNull()?.let { return it }
        val decodedCandidate = decodeInlineJavascriptEscapes(candidate)
        if (decodedCandidate == candidate) {
            return null
        }
        return runCatching { JSONObject(decodedCandidate) }.getOrNull()
    }
}

private fun decodeInlineJavascriptEscapes(source: String): String {
    if (!source.contains('\\') ||
        (!source.contains("\\x") &&
            !source.contains("\\X") &&
            !source.contains("\\u") &&
            !source.contains("\\U"))
    ) {
        return source
    }
    var normalized = source
    repeat(2) {
        val decoded = sourceEscapePattern.replace(normalized) { match ->
            match.groupValues[1]
                .ifBlank { match.groupValues[2] }
                .toInt(radix = 16)
                .toChar()
                .toString()
        }
        if (decoded == normalized) {
            return decoded
        }
        normalized = decoded
    }
    return normalized
}

private val ytcfgSetCallPattern = Regex("""ytcfg\.set\s*\(""")

private val sourceEscapePattern = Regex(
    """\\+(?:[xX]([0-9A-Fa-f]{2})|[uU]([0-9A-Fa-f]{4}))"""
)

private fun JSONObject?.findScalarDeep(fieldName: String): String {
    if (this == null) {
        return ""
    }
    scalarToString(opt(fieldName)).takeIf { it.isNotBlank() }?.let { return it }
    val keys = keys()
    while (keys.hasNext()) {
        when (val value = opt(keys.next())) {
            is JSONObject -> value.findScalarDeep(fieldName).takeIf { it.isNotBlank() }?.let { return it }
            is JSONArray -> value.findScalarDeep(fieldName).takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return ""
}

private fun JSONArray.findScalarDeep(fieldName: String): String {
    for (index in 0 until length()) {
        when (val value = opt(index)) {
            is JSONObject -> value.findScalarDeep(fieldName).takeIf { it.isNotBlank() }?.let { return it }
            is JSONArray -> value.findScalarDeep(fieldName).takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return ""
}

private fun scalarToString(value: Any?): String {
    return when (value) {
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> ""
    }
}

private fun stringFieldPattern(fieldName: String): String {
    val escapedField = Regex.escape(fieldName)
    return """(?:["']$escapedField["']|\b$escapedField\b)\s*:\s*["']([^"'\\]*(?:\\.[^"'\\]*)*)["']"""
}

private fun numberFieldPattern(fieldName: String): String {
    val escapedField = Regex.escape(fieldName)
    return """(?:["']$escapedField["']|\b$escapedField\b)\s*:\s*["']?([0-9]+)["']?"""
}

private fun booleanFieldPattern(fieldName: String): String {
    val escapedField = Regex.escape(fieldName)
    return """(?:["']$escapedField["']|\b$escapedField\b)\s*:\s*(true|false)"""
}
