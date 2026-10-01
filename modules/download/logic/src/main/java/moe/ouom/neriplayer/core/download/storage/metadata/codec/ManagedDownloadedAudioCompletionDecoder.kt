package moe.ouom.neriplayer.core.download.storage.metadata.codec

import moe.ouom.neriplayer.data.model.download.DownloadedAudioEmbeddingState
import org.json.JSONObject

internal class ManagedDownloadedAudioCompletionDecoder(private val root: JSONObject) {
    private val declaredDownloadFinalized = declaredDownloadFinalized()
    private val declaredEmbeddingState = declaredEmbeddingState()
    private val acceptsLegacyV15Completion =
        isShippedV15Completion() || isPreviouslyDowngradedV15Completion()

    fun downloadFinalized(): Boolean? {
        return if (acceptsLegacyV15Completion) true else declaredDownloadFinalized
    }

    fun embeddingState(): DownloadedAudioEmbeddingState? {
        return if (acceptsLegacyV15Completion) {
            DownloadedAudioEmbeddingState.LEGACY_V15_FINALIZED
        } else {
            declaredEmbeddingState
        }
    }

    private fun declaredDownloadFinalized(): Boolean? {
        val fieldName = "downloadFinalized"
        if (!root.has(fieldName) || root.isNull(fieldName)) {
            return null
        }
        return root.optBoolean(fieldName)
    }

    private fun declaredEmbeddingState(): DownloadedAudioEmbeddingState? {
        val fieldName = "metadataEmbeddingState"
        return DownloadedAudioEmbeddingState.fromPersisted(
            root.optString(fieldName).takeIf { root.has(fieldName) && !root.isNull(fieldName) }
        )
    }

    private fun isShippedV15Completion(): Boolean {
        return declaredDownloadFinalized == true && declaredEmbeddingState == null
    }

    private fun isPreviouslyDowngradedV15Completion(): Boolean {
        return declaredDownloadFinalized == false &&
            declaredEmbeddingState == DownloadedAudioEmbeddingState.LEGACY_UNVERIFIED &&
            root.optString("createdAtSource").equals("LEGACY_V15", ignoreCase = true) &&
            hasCompletedLegacyArtifact()
    }

    private fun hasCompletedLegacyArtifact(): Boolean {
        return root.optString("stableKey").isNotBlank() &&
            root.optString("audioFileName").isNotBlank() &&
            root.optLong("downloadTimeMs") > 0L &&
            root.optString("operationId").isBlank() &&
            hasTerminalArtifactState()
    }

    private fun hasTerminalArtifactState(): Boolean {
        val state = root.optString("artifactState")
        return state.isBlank() || state.equals("FINALIZED", ignoreCase = true) ||
            state.equals("COMPLETE", ignoreCase = true)
    }
}
