package moe.ouom.neriplayer.core.api.youtube

import java.io.IOException

/**
 * 统一处理 YouTube / YouTube Music 首页 bootstrap HTML
 * 按字段局部读取 ytcfg 和页面里的 JS 标量，避免把大段首页正文解析成对象
 */
internal class YouTubeBootstrapHtmlSource(html: String) {
    private data class SourceWindow(
        val start: Int,
        val endExclusive: Int
    )

    private data class LocalScalarValue(
        val value: String,
        val kind: BootstrapFieldValueKind
    )

    private val rawHtml = html

    /**
     * ytcfg 是首页里唯一需要完整扫描的配置块, 其余正文只保留头尾小窗口作回退
     */
    private val rawWindows by lazy(LazyThreadSafetyMode.NONE) {
        buildRelevantWindows(rawHtml)
    }

    /** 固定字段共用一次扫描, 避免每次读取可选字段都重新扫大页面 */
    private val indexedFieldValues by lazy(LazyThreadSafetyMode.NONE) {
        collectIndexedFieldValues()
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
            valueKind = BootstrapFieldValueKind.STRING
        )
    }

    fun optionalNumber(primaryField: String, vararg fallbackFields: String): String {
        return findValue(
            fieldNames = arrayOf(primaryField, *fallbackFields),
            valueKind = BootstrapFieldValueKind.NUMBER
        )
    }

    fun optionalBoolean(primaryField: String, vararg fallbackFields: String): String {
        return findValue(
            fieldNames = arrayOf(primaryField, *fallbackFields),
            valueKind = BootstrapFieldValueKind.BOOLEAN
        )
    }

    private fun findValue(
        fieldNames: Array<String>,
        valueKind: BootstrapFieldValueKind
    ): String {
        fieldNames.forEach { fieldName ->
            if (fieldName in INDEXED_FIELD_NAMES) {
                indexedFieldValues[fieldName]
                    .orEmpty()
                    .asSequence()
                    .mapNotNull { it.asRequested(valueKind) }
                    .firstOrNull { it.isNotBlank() }
                    ?.let { return it }
            } else {
                findLocalFieldValue(
                    source = rawHtml,
                    windows = rawWindows,
                    fieldName = fieldName,
                    valueKind = valueKind,
                    allowEscapedQuotes = true
                ).takeIf { it.isNotBlank() }?.let { return it }
            }
        }

        // 保留内部调用方未收录字段的兼容回退, 大首页只在相关小窗口内搜索
        fieldNames
            .filterNot(INDEXED_FIELD_NAMES::contains)
            .forEach { fieldName ->
                findLocalFieldValue(
                    source = rawHtml,
                    windows = rawWindows,
                    fieldName = fieldName,
                    valueKind = valueKind,
                    allowEscapedQuotes = false
                ).takeIf { it.isNotBlank() }?.let { return it }
            }
        return ""
    }

    private fun collectIndexedFieldValues(): Map<String, List<LocalScalarValue>> {
        val values = linkedMapOf<String, MutableList<LocalScalarValue>>()
        rawWindows.forEach { window ->
            INDEXED_FIELD_NAMES.forEach { fieldName ->
                var searchStart = window.start
                while (searchStart < window.endExclusive) {
                    val fieldIndex = rawHtml.indexOf(fieldName, searchStart)
                    if (fieldIndex < 0 || fieldIndex >= window.endExclusive) {
                        break
                    }
                    val fieldEnd = fieldIndex + fieldName.length
                    if (isFieldNameBoundary(
                            source = rawHtml,
                            fieldStart = fieldIndex,
                            fieldEnd = fieldEnd,
                            allowEscapedQuotes = true
                        )
                    ) {
                        parseLocalFieldScalarValue(
                            source = rawHtml,
                            fieldIndex = fieldIndex,
                            fieldName = fieldName,
                            endExclusive = window.endExclusive,
                            allowEscapedQuotes = true
                        )?.let { parsed ->
                            values.getOrPut(fieldName) { mutableListOf() } += parsed
                        }
                    }
                    searchStart = fieldEnd
                }
            }
        }
        return values.mapValues { (_, fieldValues) -> fieldValues.toList() }
    }

    /**
     * 只在字段名附近读取一个 JS 标量, 兼容未收录的内部字段
     */
    private fun findLocalFieldValue(
        source: String,
        windows: List<SourceWindow>,
        fieldName: String,
        valueKind: BootstrapFieldValueKind,
        allowEscapedQuotes: Boolean
    ): String {
        windows.forEach { window ->
            var searchStart = window.start
            while (searchStart < window.endExclusive) {
                val fieldIndex = source.indexOf(fieldName, startIndex = searchStart)
                if (fieldIndex < 0 || fieldIndex >= window.endExclusive) {
                    break
                }
                parseLocalFieldValue(
                    source = source,
                    fieldIndex = fieldIndex,
                    fieldName = fieldName,
                    valueKind = valueKind,
                    endExclusive = window.endExclusive,
                    allowEscapedQuotes = allowEscapedQuotes
                )?.takeIf { it.isNotBlank() }?.let { return it }
                searchStart = fieldIndex + fieldName.length
            }
        }
        return ""
    }

    private fun parseLocalFieldValue(
        source: String,
        fieldIndex: Int,
        fieldName: String,
        valueKind: BootstrapFieldValueKind,
        endExclusive: Int,
        allowEscapedQuotes: Boolean
    ): String? {
        val keyEnd = fieldIndex + fieldName.length
        if (keyEnd > endExclusive ||
            !isFieldNameBoundary(source, fieldIndex, keyEnd, allowEscapedQuotes)
        ) {
            return null
        }
        val keyValueSeparatorStart = keyEnd +
            quotedFieldSuffixLength(source, fieldIndex, keyEnd, allowEscapedQuotes)
        var valueStart = skipJsWhitespace(source, keyValueSeparatorStart, endExclusive)
        if (valueStart >= endExclusive || source[valueStart] != ':') {
            return null
        }
        valueStart = skipJsWhitespace(source, valueStart + 1, endExclusive)
        return parseLocalScalarValue(
            source = source,
            valueStart = valueStart,
            endExclusive = endExclusive,
            allowEscapedQuotes = allowEscapedQuotes
        )?.takeIf { it.supports(valueKind) }?.value
    }

    private fun parseLocalFieldScalarValue(
        source: String,
        fieldIndex: Int,
        fieldName: String,
        endExclusive: Int,
        allowEscapedQuotes: Boolean
    ): LocalScalarValue? {
        val keyEnd = fieldIndex + fieldName.length
        if (keyEnd > endExclusive ||
            !isFieldNameBoundary(source, fieldIndex, keyEnd, allowEscapedQuotes)
        ) {
            return null
        }
        val keyValueSeparatorStart = keyEnd +
            quotedFieldSuffixLength(source, fieldIndex, keyEnd, allowEscapedQuotes)
        var valueStart = skipJsWhitespace(source, keyValueSeparatorStart, endExclusive)
        if (valueStart >= endExclusive || source[valueStart] != ':') {
            return null
        }
        valueStart = skipJsWhitespace(source, valueStart + 1, endExclusive)
        return parseLocalScalarValue(
            source = source,
            valueStart = valueStart,
            endExclusive = endExclusive,
            allowEscapedQuotes = allowEscapedQuotes
        )
    }

    private fun isFieldNameBoundary(
        source: String,
        fieldStart: Int,
        fieldEnd: Int,
        allowEscapedQuotes: Boolean
    ): Boolean {
        val before = source.getOrNull(fieldStart - 1)
        val after = source.getOrNull(fieldEnd)
        if (allowEscapedQuotes &&
            quotedFieldPrefixLength(source, fieldStart) > 0 &&
            quotedFieldSuffixLength(source, fieldStart, fieldEnd, allowEscapedQuotes) > 0
        ) {
            return true
        }
        val quote = before
        if (quote == '"' || quote == '\'') {
            return after == quote
        }
        return !before.isJavaScriptIdentifierPart() && !after.isJavaScriptIdentifierPart()
    }

    private fun parseLocalScalarValue(
        source: String,
        valueStart: Int,
        endExclusive: Int,
        allowEscapedQuotes: Boolean
    ): LocalScalarValue? {
        if (valueStart >= endExclusive) {
            return null
        }
        val first = source[valueStart]
        if (first == '"' || first == '\'') {
            val valueEnd = findJsStringEnd(source, valueStart, first, endExclusive) ?: return null
            val value = source.substring(valueStart + 1, valueEnd)
            return LocalScalarValue(
                value = decodeInlineJavascriptEscapes(value),
                kind = BootstrapFieldValueKind.STRING
            )
        }
        if (allowEscapedQuotes) {
            val quoteTokenLength = javascriptQuoteTokenLength(source, valueStart)
            if (quoteTokenLength > 0) {
                val quote = javascriptQuoteToken(source, valueStart) ?: return null
                val contentStart = valueStart + quoteTokenLength
                val contentEnd = findEscapedStringEnd(
                    source = source,
                    start = contentStart,
                    quote = quote,
                    endExclusive = endExclusive
                ) ?: return null
                val value = decodeInlineJavascriptEscapes(
                    source.substring(contentStart, contentEnd)
                )
                return LocalScalarValue(
                    value = value,
                    kind = BootstrapFieldValueKind.STRING
                )
            }
        }
        readJsDigits(source, valueStart, endExclusive)?.let { digits ->
            return LocalScalarValue(
                value = digits,
                kind = BootstrapFieldValueKind.NUMBER
            )
        }
        return when {
            source.startsWith("true", valueStart) &&
                !source.getOrNull(valueStart + 4).isJavaScriptIdentifierPart() ->
                LocalScalarValue("true", BootstrapFieldValueKind.BOOLEAN)
            source.startsWith("false", valueStart) &&
                !source.getOrNull(valueStart + 5).isJavaScriptIdentifierPart() ->
                LocalScalarValue("false", BootstrapFieldValueKind.BOOLEAN)
            else -> null
        }
    }

    private fun LocalScalarValue.supports(valueKind: BootstrapFieldValueKind): Boolean {
        return asRequested(valueKind) != null
    }

    private fun LocalScalarValue.asRequested(valueKind: BootstrapFieldValueKind): String? {
        return when (valueKind) {
            BootstrapFieldValueKind.STRING -> value.takeIf {
                kind == BootstrapFieldValueKind.STRING
            }
            BootstrapFieldValueKind.NUMBER -> value.takeIf {
                (kind == BootstrapFieldValueKind.STRING ||
                    kind == BootstrapFieldValueKind.NUMBER) &&
                    it.all(Char::isDigit)
            }
            BootstrapFieldValueKind.BOOLEAN -> value.takeIf {
                kind == BootstrapFieldValueKind.BOOLEAN
            }
        }
    }

    private fun quotedFieldPrefixLength(source: String, fieldStart: Int): Int {
        val literal = source.getOrNull(fieldStart - 1)
        if (literal == '"' || literal == '\'') {
            return 1
        }
        val searchStart = maxOf(0, fieldStart - JAVASCRIPT_ESCAPE_MAX_LENGTH)
        for (candidate in searchStart until fieldStart) {
            val length = javascriptQuoteTokenLength(source, candidate)
            if (length == fieldStart - candidate && javascriptQuoteToken(source, candidate) != null) {
                return length
            }
        }
        return 0
    }

    private fun quotedFieldSuffixLength(
        source: String,
        fieldStart: Int,
        fieldEnd: Int,
        allowEscapedQuotes: Boolean
    ): Int {
        val prefixLength = quotedFieldPrefixLength(source, fieldStart)
        if (prefixLength == 0) {
            return 0
        }
        val suffixLength = if (allowEscapedQuotes) {
            javascriptQuoteTokenLength(source, fieldEnd)
        } else {
            if (source.getOrNull(fieldEnd) == source.getOrNull(fieldStart - 1)) 1 else 0
        }
        return suffixLength.takeIf {
            it > 0 && javascriptQuoteToken(source, fieldStart - prefixLength) ==
                javascriptQuoteToken(source, fieldEnd)
        } ?: 0
    }

    private fun javascriptQuoteTokenLength(source: String, start: Int): Int {
        val literal = source.getOrNull(start)
        if (literal == '"' || literal == '\'') {
            return 1
        }
        val escape = javascriptEscapeAt(source, start) ?: return 0
        return escape.length.takeIf { escape.value == '"' || escape.value == '\'' } ?: 0
    }

    private fun javascriptQuoteToken(source: String, start: Int): Char? {
        val literal = source.getOrNull(start)
        if (literal == '"' || literal == '\'') {
            return literal
        }
        return javascriptEscapeAt(source, start)
            ?.takeIf { it.value == '"' || it.value == '\'' }
            ?.value
    }

    private data class JavascriptEscape(
        val value: Char,
        val length: Int
    )

    private fun javascriptEscapeAt(source: String, start: Int): JavascriptEscape? {
        if (source.getOrNull(start) != '\\') {
            return null
        }
        var slashEnd = start
        while (source.getOrNull(slashEnd) == '\\') {
            slashEnd++
        }
        val marker = source.getOrNull(slashEnd) ?: return null
        val digitCount = when (marker) {
            'x', 'X' -> 2
            'u', 'U' -> 4
            else -> return null
        }
        val digitStart = slashEnd + 1
        val digitEnd = digitStart + digitCount
        if (digitEnd > source.length) {
            return null
        }
        var decodedValue = 0
        for (index in digitStart until digitEnd) {
            val digit = source[index].hexDigitValue()
            if (digit < 0) {
                return null
            }
            decodedValue = decodedValue * 16 + digit
        }
        return JavascriptEscape(
            value = decodedValue.toChar(),
            length = digitEnd - start
        )
    }

    private fun findEscapedStringEnd(
        source: String,
        start: Int,
        quote: Char,
        endExclusive: Int
    ): Int? {
        var index = start
        while (index < endExclusive) {
            if (source[index] == '\\') {
                val escape = javascriptEscapeAt(source, index)
                if (escape?.value == quote) {
                    return index
                }
                index += escape?.length ?: 1
            } else {
                index++
            }
        }
        return null
    }

    private fun buildRelevantWindows(source: String): List<SourceWindow> {
        val ytcfg = findYtcfgWindow(source)
        if (ytcfg == null) {
            return listOf(SourceWindow(0, source.length))
        }
        val windows = mutableListOf(ytcfg)
        val headEnd = minOf(source.length, FALLBACK_WINDOW_BYTES)
        windows += SourceWindow(0, headEnd)
        val tailStart = maxOf(0, source.length - FALLBACK_WINDOW_BYTES)
        if (tailStart < source.length) {
            windows += SourceWindow(tailStart, source.length)
        }
        return mergeWindows(windows)
    }

    private fun findYtcfgWindow(source: String): SourceWindow? {
        var searchStart = 0
        while (searchStart < source.length) {
            val marker = source.indexOf("ytcfg.set", searchStart)
            if (marker < 0) {
                return null
            }
            var opening = marker + "ytcfg.set".length
            while (opening < source.length && source[opening].isWhitespace()) {
                opening++
            }
            if (source.getOrNull(opening) != '(') {
                searchStart = marker + 1
                continue
            }
            opening++
            while (opening < source.length && source[opening].isWhitespace()) {
                opening++
            }
            if (source.getOrNull(opening) != '{') {
                searchStart = marker + 1
                continue
            }
            // 首页 ytcfg 往往带着数百 KB 的实验配置, 找对象结尾会把冷启动
            // 变成一次长时间的逐字符扫描。所需字段都在配置头部和尾部窗口内,
            // 缺失字段仍会由首页头尾回退窗口处理
            val endExclusive = minOf(source.length, opening + FAST_YTCFG_WINDOW_BYTES)
            if (YT_CFG_FIELD_MARKERS.any { fieldName ->
                    source.indexOf(fieldName, opening) in opening until endExclusive
                }
            ) {
                return SourceWindow(opening, endExclusive)
            }
            searchStart = opening + 1
        }
        return null
    }

    private fun mergeWindows(windows: List<SourceWindow>): List<SourceWindow> {
        return windows
            .filter { it.start < it.endExclusive }
            .sortedBy { it.start }
            .fold(mutableListOf()) { merged, window ->
                val previous = merged.lastOrNull()
                if (previous == null || window.start > previous.endExclusive) {
                    merged += window
                } else {
                    merged[merged.lastIndex] = SourceWindow(
                        start = previous.start,
                        endExclusive = maxOf(previous.endExclusive, window.endExclusive)
                    )
                }
                merged
            }
    }

    private fun findJsStringEnd(
        source: String,
        start: Int,
        quote: Char,
        endExclusive: Int = source.length
    ): Int? {
        var escaped = false
        for (index in start + 1 until minOf(endExclusive, source.length)) {
            val ch = source[index]
            when {
                escaped -> escaped = false
                ch == '\\' -> escaped = true
                ch == quote -> return index
            }
        }
        return null
    }

    private fun readJsDigits(
        source: String,
        start: Int,
        endExclusive: Int = source.length
    ): String? {
        var end = start
        while (end < minOf(endExclusive, source.length) && source[end].isDigit()) {
            end++
        }
        return source.substring(start, end).takeIf { it.isNotEmpty() }
    }

    private fun skipJsWhitespace(
        source: String,
        start: Int,
        endExclusive: Int = source.length
    ): Int {
        var index = start
        while (index < minOf(endExclusive, source.length) && source[index].isWhitespace()) {
            index++
        }
        return index
    }

    private companion object {
        const val FALLBACK_WINDOW_BYTES = 512 * 1024
        const val FAST_YTCFG_WINDOW_BYTES = 1024 * 1024
        const val JAVASCRIPT_ESCAPE_MAX_LENGTH = 8
        val YT_CFG_FIELD_MARKERS = setOf(
            "INNERTUBE_API_KEY",
            "INNERTUBE_CLIENT_VERSION",
            "VISITOR_DATA",
            "WEB_PLAYER_CONTEXT_CONFIGS"
        )
        val INDEXED_FIELD_NAMES = setOf(
            "DATASYNC_ID",
            "datasyncId",
            "DELEGATED_SESSION_ID",
            "INNERTUBE_API_KEY",
            "INNERTUBE_CLIENT_VERSION",
            "INNERTUBE_CONTEXT_CLIENT_VERSION",
            "LOGGED_IN",
            "SERIALIZED_COLD_HASH_DATA",
            "SERIALIZED_HOT_HASH_DATA",
            "SESSION_INDEX",
            "STS",
            "USER_SESSION_ID",
            "VISITOR_DATA",
            "WEB_PLAYER_CONTEXT_CONFIGS",
            "appInstallData",
            "coldConfigData",
            "coldHashData",
            "deviceExperimentId",
            "innertubeApiKey",
            "innertubeContextClientVersion",
            "jsUrl",
            "remoteHost",
            "rolloutToken",
            "signatureTimestamp",
            "visitorData",
            "hotHashData"
        )
    }

}

private fun decodeInlineJavascriptEscapes(source: String): String {
    if (!source.hasInlineJavascriptEscapes()) {
        return source
    }
    var normalized = source
    repeat(2) {
        val decoded = decodeInlineJavascriptEscapesOnce(normalized)
        if (decoded == normalized) {
            return decoded
        }
        normalized = decoded
    }
    return normalized
}

private fun decodeInlineJavascriptEscapesOnce(source: String): String {
    var builder: StringBuilder? = null
    var copyStart = 0
    var index = 0
    while (index < source.length) {
        if (source[index] != '\\') {
            index++
            continue
        }
        val slashStart = index
        while (index < source.length && source[index] == '\\') {
            index++
        }
        if (index >= source.length || (source[index] != 'x' && source[index] != 'X' &&
                source[index] != 'u' && source[index] != 'U')
        ) {
            continue
        }
        val digitCount = if (source[index] == 'x' || source[index] == 'X') 2 else 4
        val digitStart = index + 1
        val digitEnd = digitStart + digitCount
        if (digitEnd > source.length) {
            continue
        }
        var decodedValue = 0
        var validHex = true
        for (digitIndex in digitStart until digitEnd) {
            val digitValue = source[digitIndex].hexDigitValue()
            if (digitValue < 0) {
                validHex = false
                break
            }
            decodedValue = decodedValue * 16 + digitValue
        }
        if (!validHex) {
            continue
        }
        val decodedChar = decodedValue.toChar()
        val output = builder ?: StringBuilder(source.length).also { builder = it }
        output.append(source, copyStart, slashStart)
        output.append(decodedChar)
        copyStart = digitEnd
        index = digitEnd
    }
    val output = builder ?: return source
    output.append(source, copyStart, source.length)
    return output.toString()
}

private fun Char.hexDigitValue(): Int {
    return when (this) {
        in '0'..'9' -> code - '0'.code
        in 'a'..'f' -> code - 'a'.code + 10
        in 'A'..'F' -> code - 'A'.code + 10
        else -> -1
    }
}

private fun String.hasInlineJavascriptEscapes(): Boolean {
    return contains('\\') &&
        (contains("\\x") || contains("\\X") || contains("\\u") || contains("\\U"))
}

private enum class BootstrapFieldValueKind {
    STRING,
    NUMBER,
    BOOLEAN
}

private fun Char?.isJavaScriptIdentifierPart(): Boolean {
    val value = this ?: return false
    return value.isLetterOrDigit() || value == '_' || value == '$'
}
