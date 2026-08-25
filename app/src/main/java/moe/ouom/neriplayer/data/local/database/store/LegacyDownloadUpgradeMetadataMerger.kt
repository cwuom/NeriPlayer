package moe.ouom.neriplayer.data.local.database.store

import org.json.JSONObject

/**
 * 合并 v15 下载投影和托管 root metadata, 让迁移只负责一次性 bootstrap
 */
internal object LegacyDownloadUpgradeMetadataMerger {
    private val metadataFields = setOf(
        "stableKey",
        "songId",
        "identityAlbum",
        "album",
        "name",
        "artist",
        "coverUrl",
        "matchedLyric",
        "matchedTranslatedLyric",
        "matchedRomanizedLyric",
        "matchedLyricSource",
        "matchedSongId",
        "userLyricOffsetMs",
        "customCoverUrl",
        "customName",
        "customArtist",
        "originalName",
        "originalArtist",
        "originalCoverUrl",
        "originalLyric",
        "originalTranslatedLyric",
        "originalRomanizedLyric",
        "mediaUri",
        "channelId",
        "audioId",
        "subAudioId",
        "playlistContextId",
        "coverPath",
        "lyricPath",
        "translatedLyricPath",
        "romanizedLyricPath",
        "durationMs",
        "downloadTimeMs",
        "downloadFinalized",
        "createdAtMs",
        "createdAtSource",
        "artifactId",
        "operationId",
        "artifactState",
        "audioFileName",
        "libraryId",
        "libraryAddedAtMs",
        "sourceCreatedAtMs",
        "sourceModifiedAtMs",
        "restorableMetadata"
    )

    fun merge(
        payload: JSONObject,
        existing: JSONObject?,
        audioFileName: String
    ): JSONObject {
        val payloadMetadata = payloadMetadata(payload)
        val result = JSONObject()
        copyNonNullValues(
            source = payloadMetadata,
            target = result,
            includeUnknown = payloadMetadata !== payload
        )
        existing?.let {
            copyNonNullValues(
                source = it,
                target = result,
                includeUnknown = true
            )
        }

        val stableKey = firstNonBlank(
            existing?.optString("stableKey"),
            payloadMetadata.optString("stableKey"),
            payload.optString("stableKey")
        )
        stableKey?.let { result.put("stableKey", it) }

        val effectiveAudioName = audioFileName.trim().takeIf(String::isNotBlank)
        effectiveAudioName?.let { result.put("audioFileName", it) }

        val downloadTimeMs = firstPositiveLong(
            existing?.optLong("downloadTimeMs"),
            payloadMetadata.optLong("downloadTimeMs"),
            payload.optLong("downloadTime")
        )
        downloadTimeMs?.let {
            result.put("downloadTimeMs", it)
            if (!result.has("createdAtMs") || result.isNull("createdAtMs")) {
                result.put("createdAtMs", it)
            }
        }
        if (!result.has("createdAtSource") || result.isNull("createdAtSource")) {
            result.put("createdAtSource", "LEGACY_V15")
        }
        if (!result.has("downloadFinalized") || result.isNull("downloadFinalized")) {
            result.put("downloadFinalized", true)
        }

        val restorable = mergeRestorableMetadata(
            result = result,
            stableKey = stableKey,
            createdAtMs = downloadTimeMs
        )
        result.put("restorableMetadata", restorable)
        return result
    }

    private fun payloadMetadata(payload: JSONObject): JSONObject {
        val nested = payload.optJSONObject("metadata")
            ?: payload.optJSONObject("metadataJson")
            ?: payload.optString("metadataJson")
                .takeIf(String::isNotBlank)
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
        return nested ?: payload
    }

    private fun copyNonNullValues(
        source: JSONObject,
        target: JSONObject,
        includeUnknown: Boolean
    ) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!includeUnknown && key !in metadataFields || source.isNull(key)) continue
            target.put(key, source.get(key))
        }
    }

    private fun mergeRestorableMetadata(
        result: JSONObject,
        stableKey: String?,
        createdAtMs: Long?
    ): JSONObject {
        val restorable = JSONObject()
        val existing = result.optJSONObject("restorableMetadata")
        existing?.let { copyObject(it, restorable) }

        val sourceIdentity = restorable.optJSONObject("sourceIdentity") ?: JSONObject()
        stableKey?.let { sourceIdentity.put("stableKey", it) }
        restorable.put("sourceIdentity", sourceIdentity)

        val baseline = restorable.optJSONObject("baseline") ?: JSONObject()
        putIfMissing(baseline, "title", firstNonBlank(
            result.optString("originalName"),
            result.optString("name")
        ))
        putIfMissing(baseline, "artist", firstNonBlank(
            result.optString("originalArtist"),
            result.optString("artist")
        ))
        putIfMissing(baseline, "album", result.optString("album"))
        val customCoverReference = result.optString("customCoverUrl")
            .trim()
            .takeIf(String::isNotBlank)
        putIfMissing(baseline, "coverReference", firstNonBlank(
            result.optString("originalCoverUrl"),
            result.optString("coverUrl").takeUnless { cover ->
                cover.isBlank() || cover == customCoverReference
            }
        ))
        putIfMissing(baseline, "originalLyric", firstPresent(
            result,
            "originalLyric",
            "matchedLyric"
        ))
        putIfMissing(baseline, "translatedLyric", firstPresent(
            result,
            "originalTranslatedLyric",
            "matchedTranslatedLyric"
        ))
        putIfMissing(baseline, "romanizedLyric", firstPresent(
            result,
            "originalRomanizedLyric",
            "matchedRomanizedLyric"
        ))
        restorable.put("baseline", baseline)

        val overrides = restorable.optJSONObject("overrides") ?: JSONObject()
        putIfMissing(overrides, "title", result.optString("customName"))
        putIfMissing(overrides, "artist", result.optString("customArtist"))
        putIfMissing(overrides, "coverReference", customCoverReference)
        putIfMissing(overrides, "originalLyric", firstPresent(result, "matchedLyric"))
        putIfMissing(
            overrides,
            "translatedLyric",
            firstPresent(result, "matchedTranslatedLyric")
        )
        putIfMissing(
            overrides,
            "romanizedLyric",
            firstPresent(result, "matchedRomanizedLyric")
        )
        restorable.put("overrides", overrides)

        val times = restorable.optJSONObject("times") ?: JSONObject()
        putIfMissing(times, "createdAtMs", firstPositiveLong(
            result.optLong("createdAtMs"),
            createdAtMs
        ))
        putIfMissing(times, "updatedAtMs", firstPositiveLong(result.optLong("updatedAtMs")))
        if (!times.has("updatedAtMs") && times.has("createdAtMs")) {
            times.put("updatedAtMs", times.optLong("createdAtMs"))
        }
        restorable.put("times", times)
        if (!restorable.has("assetRefs")) {
            restorable.put("assetRefs", JSONObject())
        }
        return restorable
    }

    private fun copyObject(source: JSONObject, target: JSONObject) {
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (!source.isNull(key)) {
                target.put(key, source.get(key))
            }
        }
    }

    private fun putIfMissing(target: JSONObject, key: String, value: Any?) {
        if (target.has(key) && !target.isNull(key)) return
        when (value) {
            null -> Unit
            is String -> value.takeIf(String::isNotBlank)?.let { target.put(key, it) }
            else -> target.put(key, value)
        }
    }

    private fun firstPresent(root: JSONObject, vararg keys: String): String? {
        return keys.asSequence()
            .mapNotNull { key ->
                if (!root.has(key) || root.isNull(key)) null else root.optString(key)
            }
            .firstOrNull(String::isNotBlank)
    }

    private fun firstNonBlank(vararg values: String?): String? {
        return values.firstOrNull { !it.isNullOrBlank() }
    }

    private fun firstPositiveLong(vararg values: Long?): Long? {
        return values.firstOrNull { it != null && it > 0L }
    }
}
