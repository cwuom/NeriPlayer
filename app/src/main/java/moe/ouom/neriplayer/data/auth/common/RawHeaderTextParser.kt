package moe.ouom.neriplayer.data.auth.common

fun parseRawHeaderText(raw: String): LinkedHashMap<String, String> {
    val headers = linkedMapOf<String, String>()
    raw.lineSequence()
        .map(String::trim)
        .filter { it.isNotBlank() && !it.startsWith(':') }
        .forEach { line ->
            val delimiterIndex = line.indexOf(':')
            if (delimiterIndex <= 0) {
                return@forEach
            }
            val key = line.substring(0, delimiterIndex).trim().lowercase()
            val value = line.substring(delimiterIndex + 1).trim()
            if (key.isNotBlank() && value.isNotBlank()) {
                headers[key] = value
            }
        }
    return headers
}
