package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * ytcfg 里的标量按 DFS 前序摊平，同名取最先出现的一个
     *
     * 逐字段递归时每个字段都要走一遍完整 JSON 树，而 ytcfg 带着上千条
     * EXPERIMENT_FLAGS，二十来个字段就是二十遍全树遍历
     */
    private val ytcfgScalars by lazy(LazyThreadSafetyMode.NONE) {
        val scalars = HashMap<String, String>()
        ytcfg?.let { collectScalarsDeep(it, scalars) }
        scalars
    }

    private val hasInlineEscapes by lazy(LazyThreadSafetyMode.NONE) {
        rawHtml.hasInlineJavascriptEscapes()
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
        val scalars = ytcfgScalars
        fieldNames.forEach { fieldName ->
            scalars[fieldName]?.let { return it }
        }

        findRegexValue(rawHtml, fieldNames, patternBuilder)
            .takeIf { it.isNotBlank() }
            ?.let { return it }

        // 没有转义序列时解码必然原样返回，别为此复制一份 MB 级字符串
        if (!hasInlineEscapes) {
            return ""
        }

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
            // 模式里字段名是字面量，名字都不在文档里就不必扫一遍 MB 级正文
            .map { fieldName -> fieldName to source.indexOf(fieldName) }
            .filter { (_, fieldIndex) -> fieldIndex >= 0 }
            .map { (fieldName, fieldIndex) ->
                compiledFieldPattern(patternBuilder(fieldName))
                    .find(source, startIndex = regexStartIndexForField(fieldIndex))
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
    if (!source.hasInlineJavascriptEscapes()) {
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

/**
 * 正则从字段名首次出现处起扫，不必从头再刷一遍 MB 级正文
 *
 * 匹配必然包含字段名，所以不可能落在首次出现之前；
 * 模式允许名字前带一个引号，留一个字符余量就够
 */
private fun regexStartIndexForField(fieldIndex: Int): Int = (fieldIndex - 1).coerceAtLeast(0)

private val ytcfgSetCallPattern = Regex("""ytcfg\.set\s*\(""")

private val sourceEscapePattern = Regex(
    """\\+(?:[xX]([0-9A-Fa-f]{2})|[uU]([0-9A-Fa-f]{4}))"""
)

private fun collectScalarsDeep(node: JSONObject, into: MutableMap<String, String>) {
    // 本层标量先落表再下钻，保证浅层同名字段压过深层的
    val children = ArrayList<Any>()
    val keys = node.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val value = node.opt(key)) {
            is JSONObject -> children.add(value)
            is JSONArray -> children.add(value)
            else -> {
                val scalar = scalarToString(value)
                if (scalar.isNotBlank() && !into.containsKey(key)) {
                    into[key] = scalar
                }
            }
        }
    }
    children.forEach { child ->
        when (child) {
            is JSONObject -> collectScalarsDeep(child, into)
            is JSONArray -> collectScalarsDeep(child, into)
        }
    }
}

private fun collectScalarsDeep(node: JSONArray, into: MutableMap<String, String>) {
    for (index in 0 until node.length()) {
        when (val value = node.opt(index)) {
            is JSONObject -> collectScalarsDeep(value, into)
            is JSONArray -> collectScalarsDeep(value, into)
        }
    }
}

private val compiledFieldPatterns = ConcurrentHashMap<String, Regex>()

private fun compiledFieldPattern(pattern: String): Regex {
    return compiledFieldPatterns.getOrPut(pattern) { Regex(pattern) }
}

private fun String.hasInlineJavascriptEscapes(): Boolean {
    return contains('\\') &&
        (contains("\\x") || contains("\\X") || contains("\\u") || contains("\\U"))
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
