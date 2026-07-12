package moe.ouom.neriplayer.core.download.storage.metadata

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.util.NPLogger
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
        rewriteMetadataReferenceField(root, "coverUrl", referenceMap)
        rewriteMetadataReferenceField(root, "originalCoverUrl", referenceMap)
        rewriteMetadataReferenceField(root, "mediaUri", referenceMap)
        rewriteMetadataEmbeddedReferenceField(root, "stableKey", referenceMap)
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
        val updated = referenceMap.entries.fold(current) { value, (from, to) ->
            if (value.contains(from)) {
                value.replace(from, to)
            } else {
                value
            }
        }
        if (updated != current) {
            root.put(fieldName, updated)
        }
    }
}
