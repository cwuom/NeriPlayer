package moe.ouom.neriplayer.core.download.storage.metadata

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject

internal object ManagedDownloadMetadataCodec {
    private const val TAG = "ManagedDownloadStorage"

    fun rewriteManagedMetadataReferences(
        rawJson: String,
        referenceMap: Map<String, String>
    ): String {
        if (referenceMap.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        rewriteMetadataReferenceField(root, "coverPath", referenceMap)
        rewriteMetadataReferenceField(root, "lyricPath", referenceMap)
        rewriteMetadataReferenceField(root, "translatedLyricPath", referenceMap)
        rewriteMetadataReferenceField(root, "romanizedLyricPath", referenceMap)
        rewriteMetadataReferenceField(root, "coverUrl", referenceMap)
        rewriteMetadataReferenceField(root, "originalCoverUrl", referenceMap)
        rewriteMetadataReferenceField(root, "mediaUri", referenceMap)
        rewriteMetadataReferenceField(root, "localFilePath", referenceMap)
        rewriteMetadataEmbeddedReferenceField(root, "stableKey", referenceMap)
        root.optJSONObject("restorableMetadata")?.let { restorable ->
            rewriteMetadataReferenceField(restorable, "coverReference", referenceMap)
            restorable.optJSONObject("baseline")?.let { baseline ->
                rewriteMetadataReferenceField(baseline, "coverReference", referenceMap)
            }
            restorable.optJSONObject("overrides")?.let { overrides ->
                rewriteMetadataReferenceField(overrides, "coverReference", referenceMap)
            }
        }
        return root.toString()
    }

    fun parseDownloadedAudioMetadataJson(
        rawJson: String
    ): ManagedDownloadStorage.DownloadedAudioMetadata? {
        return runCatching {
            ManagedDownloadStorageJsonCodec.downloadedAudioMetadataFromJsonObject(JSONObject(rawJson))
        }.onFailure {
            NPLogger.w(TAG, "解析写回元数据失败: ${it.message}")
        }.getOrNull()
    }

    fun finalizedDownloadedMetadataJson(rawJson: String): String? {
        return runCatching {
            JSONObject(rawJson).apply {
                put("downloadFinalized", true)
            }.toString()
        }.onFailure {
            NPLogger.w(TAG, "恢复 finalized 元数据失败: ${it.message}")
        }.getOrNull()
    }

    fun isMetadataWriteVerified(
        expected: ManagedDownloadStorage.DownloadedAudioMetadata,
        actual: ManagedDownloadStorage.DownloadedAudioMetadata?
    ): Boolean {
        return actual == expected
    }

    private fun rewriteMetadataReferenceField(
        root: JSONObject,
        fieldName: String,
        referenceMap: Map<String, String>
    ) {
        val current = root.optString(fieldName).takeIf(String::isNotBlank) ?: return
        val updated = referenceMap[current] ?: return
        root.put(fieldName, updated)
    }

    private fun rewriteMetadataEmbeddedReferenceField(
        root: JSONObject,
        fieldName: String,
        referenceMap: Map<String, String>
    ) {
        val current = root.optString(fieldName).takeIf(String::isNotBlank) ?: return
        val replacements = referenceMap.entries
            .filter { (from, to) -> from.isNotBlank() && from != to }
            .sortedWith(
                compareByDescending<Map.Entry<String, String>> { entry -> entry.key.length }
                    .thenBy { entry -> entry.key }
            )
        if (replacements.isEmpty()) return
        val updated = buildString(current.length) {
            var index = 0
            while (index < current.length) {
                val replacement = replacements.firstOrNull { entry ->
                    current.startsWith(entry.key, startIndex = index)
                }
                if (replacement == null) {
                    append(current[index])
                    index++
                } else {
                    append(replacement.value)
                    index += replacement.key.length
                }
            }
        }
        if (updated != current) {
            root.put(fieldName, updated)
        }
    }
}
