package moe.ouom.neriplayer.data.local.database.migration.legacy

import org.json.JSONObject

internal fun mergeLegacyRow(
    payload: JSONObject,
    tableName: String,
    stableKey: String,
    row: JSONObject
): Boolean {
    if (tableName == "download_snapshot_entry") {
        val entries = payload.optJSONArray("download_snapshot_entries")
            ?: org.json.JSONArray().also {
                payload.put("download_snapshot_entries", it)
            }
        if (!containsJsonObject(entries, row)) {
            entries.put(row)
            return true
        }
        return false
    }
    val previous = payload.optJSONObject(tableName)
    if (previous == null) {
        payload.put(tableName, row)
        addCamelCaseAliases(payload, row)
        return true
    }

    return when (compareLegacyBytes(previous, row)) {
        true -> false
        false -> {
            appendLegacyConflict(
                payload = payload,
                tableName = tableName,
                stableKey = stableKey,
                reason = "SAME_STABLE_KEY_DIFFERENT_BYTES",
                previous = previous,
                duplicate = row
            )
            true
        }
        null -> {
            appendLegacyConflict(
                payload = payload,
                tableName = tableName,
                stableKey = stableKey,
                reason = "SAME_STABLE_KEY_BYTES_UNVERIFIED",
                previous = previous,
                duplicate = row
            )
            true
        }
    }
}

internal fun compareLegacyBytes(first: JSONObject, second: JSONObject): Boolean? {
    val firstFingerprint = legacyByteFingerprint(first) ?: return null
    val secondFingerprint = legacyByteFingerprint(second) ?: return null
    return firstFingerprint == secondFingerprint
}

internal fun legacyByteFingerprint(row: JSONObject): String? {
    val contentHash = listOf("content_hash", "contentHash")
        .asSequence()
        .map { key -> row.optString(key) }
        .firstOrNull(String::isNotBlank)
    if (contentHash != null) return "hash:${contentHash.trim()}"

    return null
}

internal fun containsJsonObject(
    array: org.json.JSONArray,
    candidate: JSONObject
): Boolean {
    for (index in 0 until array.length()) {
        if (array.optJSONObject(index)?.toString() == candidate.toString()) {
            return true
        }
    }
    return false
}

internal fun appendLegacyConflict(
    payload: JSONObject,
    tableName: String,
    stableKey: String,
    reason: String,
    previous: JSONObject,
    duplicate: JSONObject
) {
    val conflicts = payload.optJSONArray("legacyConflicts") ?: org.json.JSONArray()
    conflicts.put(
        JSONObject().apply {
            put("table", tableName)
            put("stableKey", stableKey)
            put("reason", reason)
            put("firstFingerprint", legacyByteFingerprint(previous))
            put("duplicateFingerprint", legacyByteFingerprint(duplicate))
            put("firstReference", legacyReference(previous))
            put("duplicateReference", legacyReference(duplicate))
            put("previous", JSONObject(previous.toString()))
            put("duplicate", JSONObject(duplicate.toString()))
        }
    )
    payload.put("legacyConflicts", conflicts)
}

internal fun legacyReference(row: JSONObject): String? {
    return listOf(
        "media_uri",
        "mediaUri",
        "file_path",
        "filePath",
        "audio_reference",
        "audioReference"
    ).firstNotNullOfOrNull { key ->
        legacyReferenceValue(row.opt(key))
    }
}

private fun legacyReferenceValue(value: Any?): String? {
    if (value == null || value == JSONObject.NULL) return null
    return value.toString().trim().takeIf(String::isNotBlank)
}

internal fun addCamelCaseAliases(target: JSONObject, row: JSONObject) {
    val keys = row.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        val alias = snakeToCamel(key)
        if (alias.isNotBlank() && (!target.has(alias) || target.isNull(alias))) {
            target.put(alias, row.get(key))
        }
        if (key == "audio_name" && (!target.has("audioFileName") || target.isNull("audioFileName"))) {
            target.put("audioFileName", row.get(key))
        }
    }
}

internal fun snakeToCamel(value: String): String {
    return buildString(value.length) {
        var uppercaseNext = false
        value.forEach { character ->
            if (character == '_') {
                uppercaseNext = true
            } else if (uppercaseNext) {
                append(character.uppercaseChar())
                uppercaseNext = false
            } else {
                append(character)
            }
        }
    }
}
