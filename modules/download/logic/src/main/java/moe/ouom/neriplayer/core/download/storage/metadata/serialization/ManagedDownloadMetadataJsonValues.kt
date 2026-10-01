package moe.ouom.neriplayer.core.download.storage.metadata.serialization

import org.json.JSONArray
import org.json.JSONObject

internal fun JSONObject?.optionalString(name: String): String? {
    this ?: return null
    return optString(name).takeIf { has(name) && !isNull(name) && it.isNotBlank() }
}

internal fun JSONObject.optionalLyric(name: String): String? =
    optString(name).takeIf { has(name) && !isNull(name) }

internal fun JSONObject?.optionalLong(name: String): Long? {
    this ?: return null
    return optLong(name).takeIf { has(name) && !isNull(name) && it > 0L }
}

internal fun JSONObject.optionalOffset(primaryName: String, legacyName: String): Long {
    val name = when {
        has(primaryName) && !isNull(primaryName) -> primaryName
        has(legacyName) && !isNull(legacyName) -> legacyName
        else -> return 0L
    }
    return optLong(name)
}

internal fun JSONObject?.stringList(name: String): List<String> =
    this?.optJSONArray(name)?.toStringList().orEmpty()

private fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) {
        optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
    }
}.distinct()
