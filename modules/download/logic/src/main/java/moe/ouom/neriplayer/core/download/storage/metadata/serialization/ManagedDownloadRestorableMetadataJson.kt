package moe.ouom.neriplayer.core.download.storage.metadata.serialization

import moe.ouom.neriplayer.data.model.download.ManagedDownloadRestorableMetadata
import org.json.JSONArray
import org.json.JSONObject

fun ManagedDownloadRestorableMetadata.toJson(): JSONObject {
    return JSONObject().apply {
        put("sourceIdentity", JSONObject().apply {
            put("stableKey", sourceStableKey)
        })
        put("baseline", baseline.toJson())
        put("overrides", overrides.toJson())
        put("assetRefs", JSONObject().apply {
            put("baselineCoverHash", baselineCoverAssetHash)
            put("currentCoverHash", currentCoverAssetHash)
            put("baselineCoverFileName", baselineCoverAssetFileName)
            put("currentCoverFileName", currentCoverAssetFileName)
            put(
                "legacyCoverRecoveryReferences",
                JSONArray().apply {
                    legacyCoverRecoveryReferences.forEach(::put)
                }
            )
        })
        put("times", JSONObject().apply {
            put("createdAtMs", createdAtMs)
            put("updatedAtMs", updatedAtMs)
        })
    }
}

fun ManagedDownloadRestorableMetadata.Companion.fromJson(root: JSONObject?): ManagedDownloadRestorableMetadata? {
    root ?: return null
    val baseline = root.optJSONObject("baseline").toBaseline()
    val overrides = root.optJSONObject("overrides").toOverrides()
    val assets = root.optJSONObject("assetRefs")
    val times = root.optJSONObject("times")
    return ManagedDownloadRestorableMetadata(
        sourceStableKey = root.sourceStableKey(),
        baseline = baseline,
        overrides = overrides,
        baselineCoverAssetHash = assets.optionalString("baselineCoverHash"),
        currentCoverAssetHash = assets.optionalString("currentCoverHash"),
        baselineCoverAssetFileName = assets.optionalString("baselineCoverFileName"),
        currentCoverAssetFileName = assets.optionalString("currentCoverFileName"),
        legacyCoverRecoveryReferences = assets.stringList("legacyCoverRecoveryReferences"),
        createdAtMs = times.optionalLong("createdAtMs"),
        updatedAtMs = times.optionalLong("updatedAtMs")
    )
}

private fun JSONObject.sourceStableKey(): String? =
    optJSONObject("sourceIdentity").optionalString("stableKey") ?: optionalString("sourceStableKey")

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

private fun JSONObject?.toBaseline(): ManagedDownloadRestorableMetadata.Baseline {
    this ?: return ManagedDownloadRestorableMetadata.Baseline()
    return ManagedDownloadRestorableMetadata.Baseline(
        title = optionalString("title"),
        artist = optionalString("artist"),
        album = optionalString("album"),
        coverReference = optionalString("coverReference"),
        originalLyric = optionalLyric("originalLyric"),
        translatedLyric = optionalLyric("translatedLyric"),
        romanizedLyric = optionalLyric("romanizedLyric")
    )
}

private fun JSONObject?.toOverrides(): ManagedDownloadRestorableMetadata.Overrides {
    this ?: return ManagedDownloadRestorableMetadata.Overrides()
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
