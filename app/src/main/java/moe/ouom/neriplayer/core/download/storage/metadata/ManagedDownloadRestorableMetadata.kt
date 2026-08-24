package moe.ouom.neriplayer.core.download.storage.metadata

import org.json.JSONObject

internal data class ManagedDownloadRestorableMetadata(
    val sourceStableKey: String?,
    val baseline: Baseline,
    val overrides: Overrides,
    val baselineCoverAssetHash: String? = null,
    val currentCoverAssetHash: String? = null,
    val createdAtMs: Long? = null,
    val updatedAtMs: Long? = null
) {
    data class Baseline(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val coverReference: String? = null,
        val originalLyric: String? = null,
        val translatedLyric: String? = null,
        val romanizedLyric: String? = null
    )

    data class Overrides(
        val title: String? = null,
        val artist: String? = null,
        val coverReference: String? = null,
        val userLyricOffsetMs: Long = 0L,
        val originalLyric: String? = null,
        val translatedLyric: String? = null,
        val romanizedLyric: String? = null
    )

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("sourceIdentity", JSONObject().apply {
                put("stableKey", sourceStableKey)
            })
            put("baseline", baseline.toJson())
            put("overrides", overrides.toJson())
            put("assetRefs", JSONObject().apply {
                put("baselineCoverHash", baselineCoverAssetHash)
                put("currentCoverHash", currentCoverAssetHash)
            })
            put("times", JSONObject().apply {
                put("createdAtMs", createdAtMs)
                put("updatedAtMs", updatedAtMs)
            })
        }
    }

    companion object {
        fun fromJson(root: JSONObject?): ManagedDownloadRestorableMetadata? {
            root ?: return null
            val sourceIdentity = root.optJSONObject("sourceIdentity")
            val sourceStableKey = sourceIdentity?.optionalString("stableKey")
                ?: root.optionalString("sourceStableKey")
            val baseline = root.optJSONObject("baseline")?.toBaseline() ?: Baseline()
            val overrides = root.optJSONObject("overrides")?.toOverrides() ?: Overrides()
            val assets = root.optJSONObject("assetRefs")
            val times = root.optJSONObject("times")
            return ManagedDownloadRestorableMetadata(
                sourceStableKey = sourceStableKey,
                baseline = baseline,
                overrides = overrides,
                baselineCoverAssetHash = assets?.optionalString("baselineCoverHash"),
                currentCoverAssetHash = assets?.optionalString("currentCoverHash"),
                createdAtMs = times?.optionalLong("createdAtMs"),
                updatedAtMs = times?.optionalLong("updatedAtMs")
            )
        }
    }
}

private fun ManagedDownloadRestorableMetadata.Baseline.toJson(): JSONObject {
    return JSONObject().apply {
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("coverReference", coverReference)
        put("originalLyric", originalLyric)
        put("translatedLyric", translatedLyric)
        put("romanizedLyric", romanizedLyric)
    }
}

private fun ManagedDownloadRestorableMetadata.Overrides.toJson(): JSONObject {
    return JSONObject().apply {
        put("title", title)
        put("artist", artist)
        put("coverReference", coverReference)
        put("userLyricOffsetMs", userLyricOffsetMs)
        put("originalLyric", originalLyric)
        put("translatedLyric", translatedLyric)
        put("romanizedLyric", romanizedLyric)
    }
}

private fun JSONObject.toBaseline(): ManagedDownloadRestorableMetadata.Baseline {
    return ManagedDownloadRestorableMetadata.Baseline(
        title = optionalString("title"),
        artist = optionalString("artist"),
        album = optionalString("album"),
        coverReference = optionalString("coverReference"),
        originalLyric = optionalString("originalLyric"),
        translatedLyric = optionalString("translatedLyric"),
        romanizedLyric = optionalString("romanizedLyric")
    )
}

private fun JSONObject.toOverrides(): ManagedDownloadRestorableMetadata.Overrides {
    return ManagedDownloadRestorableMetadata.Overrides(
        title = optionalString("title"),
        artist = optionalString("artist"),
        coverReference = optionalString("coverReference"),
        userLyricOffsetMs = optionalOffset("userLyricOffsetMs", "lyricOffsetMs"),
        originalLyric = optionalString("originalLyric"),
        translatedLyric = optionalString("translatedLyric"),
        romanizedLyric = optionalString("romanizedLyric")
    )
}

private fun JSONObject.optionalString(name: String): String? {
    return optString(name).takeIf { has(name) && !isNull(name) && it.isNotBlank() }
}

private fun JSONObject.optionalLong(name: String): Long? {
    return optLong(name).takeIf { has(name) && !isNull(name) && it > 0L }
}

private fun JSONObject.optionalOffset(primaryName: String, legacyName: String): Long {
    val name = when {
        has(primaryName) && !isNull(primaryName) -> primaryName
        has(legacyName) && !isNull(legacyName) -> legacyName
        else -> return 0L
    }
    return optLong(name)
}
