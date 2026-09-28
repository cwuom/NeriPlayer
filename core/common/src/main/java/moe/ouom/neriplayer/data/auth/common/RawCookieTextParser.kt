package moe.ouom.neriplayer.data.auth.common

private val rawCookieDelimiterRegex = Regex("[;\\r\\n]+")

fun parseRawCookieText(raw: String): LinkedHashMap<String, String> {
    val cookies = linkedMapOf<String, String>()
    raw.split(rawCookieDelimiterRegex)
        .asSequence()
        .map(String::trim)
        .filter { it.isNotBlank() }
        .forEach { segment ->
            val delimiterIndex = segment.indexOf('=')
            if (delimiterIndex <= 0) {
                return@forEach
            }
            val key = segment.substring(0, delimiterIndex).trim()
            val value = segment.substring(delimiterIndex + 1).trim()
            if (key.isNotEmpty() && value.isNotEmpty()) {
                cookies[key] = value
            }
        }
    return cookies
}
