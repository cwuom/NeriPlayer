package moe.ouom.neriplayer.core.download.storage.metadata

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.DownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.isAcceptedDownloadedAudioEmbeddingState
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.logging.NPLogger
import org.json.JSONObject

internal data class ManagedMetadataReferenceReplacement(
    val from: String,
    val to: String
)

internal fun prepareManagedMetadataReferenceReplacements(
    referenceMap: Map<String, String>
): List<ManagedMetadataReferenceReplacement> {
    return referenceMap.entries
        .filter { (from, to) -> from.isNotBlank() && from != to }
        .sortedWith(
            compareByDescending<Map.Entry<String, String>> { entry -> entry.key.length }
                .thenBy { entry -> entry.key }
        )
        .map { entry ->
            ManagedMetadataReferenceReplacement(
                from = entry.key,
                to = entry.value
            )
        }
}

internal object ManagedDownloadMetadataCodec {
    private const val TAG = "ManagedDownloadStorage"

    fun rewriteManagedMetadataReferences(
        rawJson: String,
        referenceMap: Map<String, String>
    ): String {
        return rewriteManagedMetadataReferences(
            rawJson = rawJson,
            referenceMap = referenceMap,
            sortedReplacements = prepareManagedMetadataReferenceReplacements(referenceMap)
        )
    }

    internal fun rewriteManagedMetadataReferences(
        rawJson: String,
        referenceMap: Map<String, String>,
        sortedReplacements: List<ManagedMetadataReferenceReplacement>
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
        rewriteMetadataEmbeddedReferenceField(root, "stableKey", sortedReplacements)
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
            val metadata = JSONObject(rawJson)
            val embeddingState = DownloadedAudioEmbeddingState.fromPersisted(
                metadata.optString("metadataEmbeddingState").takeIf {
                    metadata.has("metadataEmbeddingState") &&
                        !metadata.isNull("metadataEmbeddingState")
                }
            )
            if (!isAcceptedDownloadedAudioEmbeddingState(embeddingState)) {
                return@runCatching null
            }
            metadata.apply {
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
        replacements: List<ManagedMetadataReferenceReplacement>
    ) {
        val current = root.optString(fieldName).takeIf(String::isNotBlank) ?: return
        if (replacements.isEmpty()) return
        val updated = buildString(current.length) {
            var index = 0
            while (index < current.length) {
                val replacement = replacements.firstOrNull { entry ->
                    current.startsWith(entry.from, startIndex = index)
                }
                if (replacement == null) {
                    append(current[index])
                    index++
                } else {
                    append(replacement.to)
                    index += replacement.from.length
                }
            }
        }
        if (updated != current) {
            root.put(fieldName, updated)
        }
    }
}
