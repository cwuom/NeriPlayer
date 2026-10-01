package moe.ouom.neriplayer.platform.search.api.codec

import java.util.Base64

private val QQMusicBase64Pattern = Regex("^[A-Za-z0-9+/=]+$")
private val QQMusicLrcTimestampPattern = Regex(
    "\\[(\\d{1,3}):(\\d{2})(?:[.:]\\d{1,3})?]"
)

/**
 * QQ 用一行 // 占位表示这句没有翻译
 *
 * 保留时间戳让翻译 matcher 可以消费这个空槽, 最终显示层会忽略占位文本
 */
fun stripUntranslatedPlaceholderLines(lyric: String?): String? {
    val source = lyric?.takeIf { it.isNotBlank() } ?: return null
    return source.lineSequence()
        .joinToString("\n") { line ->
            val closingBracket = line.indexOf(']')
            if (closingBracket < 0) {
                line
            } else {
                val text = line.substring(closingBracket + 1).trim()
                if (isQQMusicUntranslatedPlaceholder(text)) {
                    line.substring(0, closingBracket + 1) + "//"
                } else {
                    line
                }
            }
        }
        .takeIf { it.isNotBlank() }
}

private fun isQQMusicUntranslatedPlaceholder(text: String): Boolean {
    val normalized = text
        .replace('／', '/')
        .filterNot(Char::isWhitespace)
    return normalized.length >= 2 && normalized.all { it == '/' }
}

fun decodeQQMusicLyricPayload(rawValue: String?): String? {
    val sanitized = rawValue?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val plainText = htmlUnescapeQQMusic(sanitized)
    if (QQMusicLrcTimestampPattern.containsMatchIn(plainText)) {
        return plainText
    }
    val decoded = decodeQQMusicBase64Lyric(plainText) ?: return null
    return htmlUnescapeQQMusic(decoded)
        .takeIf { QQMusicLrcTimestampPattern.containsMatchIn(it) }
}

fun decodeQQMusicBase64Lyric(value: String): String? {
    val compact = value.filterNot(Char::isWhitespace)
    if (compact.isEmpty() || compact.length % 4 != 0 || !QQMusicBase64Pattern.matches(compact)) {
        return null
    }
    return runCatching {
        String(Base64.getDecoder().decode(compact), Charsets.UTF_8)
    }.getOrNull()?.takeIf { QQMusicLrcTimestampPattern.containsMatchIn(it) }
}

private fun htmlUnescapeQQMusic(value: String): String {
    return value
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&quot;", "\"")
        .replace("&amp;", "&")
}
