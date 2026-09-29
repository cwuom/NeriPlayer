package moe.ouom.neriplayer.core.download.storage.metadata.codec

import moe.ouom.neriplayer.core.download.storage.metadata.serialization.fromJson

import moe.ouom.neriplayer.data.model.download.DownloadedAudioMetadata
import moe.ouom.neriplayer.data.model.download.ManagedDownloadRestorableMetadata
import org.json.JSONObject

class ManagedDownloadedAudioMetadataDecoder(private val root: JSONObject) {
    private val restorable = ManagedDownloadRestorableMetadata.fromJson(
        root.optJSONObject("restorableMetadata")
    )
    private val baseline = restorable?.baseline ?: ManagedDownloadRestorableMetadata.Baseline()
    private val overrides = restorable?.overrides ?: ManagedDownloadRestorableMetadata.Overrides()
    private val completion = ManagedDownloadedAudioCompletionDecoder(root)

    fun decode(): DownloadedAudioMetadata {
        return DownloadedAudioMetadata(
            stableKey = nonblankString("stableKey", restorable?.sourceStableKey),
            songId = positiveLong("songId"),
            identityAlbum = nonblankString("identityAlbum"),
            album = nonblankString("album"),
            name = nonblankString("name", baseline.title),
            artist = nonblankString("artist", baseline.artist),
            coverUrl = nonblankString("coverUrl"),
            matchedLyric = overriddenLyric("matchedLyric", overrides.originalLyric),
            matchedTranslatedLyric = overriddenLyric(
                "matchedTranslatedLyric", overrides.translatedLyric
            ),
            matchedRomanizedLyric = overriddenLyric(
                "matchedRomanizedLyric", overrides.romanizedLyric
            ),
            matchedLyricSource = nonblankString("matchedLyricSource"),
            matchedSongId = nonblankString("matchedSongId"),
            userLyricOffsetMs = lyricOffsetMs(),
            customCoverUrl = nonblankString("customCoverUrl"),
            customName = nonblankString("customName", overrides.title),
            customArtist = nonblankString("customArtist", overrides.artist),
            originalName = nonblankString("originalName", baseline.title),
            originalArtist = nonblankString("originalArtist", baseline.artist),
            originalCoverUrl = nonblankString("originalCoverUrl", baseline.coverReference),
            originalLyric = presentString("originalLyric", baseline.originalLyric),
            originalTranslatedLyric = presentString(
                "originalTranslatedLyric", baseline.translatedLyric
            ),
            originalRomanizedLyric = presentString(
                "originalRomanizedLyric", baseline.romanizedLyric
            ),
            mediaUri = nonblankString("mediaUri"),
            channelId = nonblankString("channelId"),
            audioId = nonblankString("audioId"),
            subAudioId = nonblankString("subAudioId"),
            playlistContextId = nonblankString("playlistContextId"),
            coverPath = nonblankString(
                "coverPath", overrides.coverReference ?: baseline.coverReference
            ),
            lyricPath = nonblankString("lyricPath"),
            translatedLyricPath = nonblankString("translatedLyricPath"),
            romanizedLyricPath = nonblankString("romanizedLyricPath"),
            durationMs = root.optLong("durationMs"),
            verifiedAudioDurationMs = positiveLong("verifiedAudioDurationMs"),
            downloadTimeMs = positiveTimestamp("downloadTimeMs"),
            downloadFinalized = completion.downloadFinalized(),
            audioPublicationPending = root.optBoolean("audioPublicationPending", false),
            audioPublicationOwnerId = nonblankString("audioPublicationOwnerId"),
            metadataEmbeddingState = completion.embeddingState(),
            createdAtMs = positiveTimestamp("createdAtMs", restorable?.createdAtMs),
            createdAtSource = nonblankString("createdAtSource"),
            createdAtConfidence = nonblankString("createdAtConfidence"),
            artifactId = nonblankString("artifactId"),
            operationId = nonblankString("operationId"),
            terminalTemporaryWriteCleanupToken = nonblankString("terminalTemporaryWriteCleanupToken"),
            artifactState = nonblankString("artifactState"),
            audioFileName = nonblankString("audioFileName"),
            libraryId = nonblankString("libraryId"),
            libraryAddedAtMs = positiveTimestamp("libraryAddedAtMs"),
            sourceCreatedAtMs = positiveTimestamp("sourceCreatedAtMs"),
            sourceModifiedAtMs = positiveTimestamp("sourceModifiedAtMs"),
            restorableMetadata = restorable
        )
    }

    private fun nonblankString(fieldName: String, fallback: String? = null): String? {
        return root.optString(fieldName).takeIf(String::isNotBlank) ?: fallback
    }

    private fun presentString(fieldName: String, fallback: String? = null): String? {
        if (!root.has(fieldName) || root.isNull(fieldName)) {
            return fallback
        }
        return root.optString(fieldName)
    }

    private fun overriddenLyric(fieldName: String, overrideLyric: String?): String? {
        return overrideLyric ?: presentString(fieldName)
    }

    private fun positiveLong(fieldName: String): Long? {
        return root.optLong(fieldName).takeIf { it > 0L }
    }

    private fun positiveTimestamp(fieldName: String, fallback: Long? = null): Long? {
        val value = root.optLong(fieldName)
        return if (root.has(fieldName) && value > 0L) value else fallback
    }

    private fun lyricOffsetMs(): Long {
        val fieldName = "userLyricOffsetMs"
        val value = root.optLong(fieldName)
        if (!root.has(fieldName) || root.isNull(fieldName) || value == 0L) {
            return overrides.userLyricOffsetMs
        }
        return value
    }
}
