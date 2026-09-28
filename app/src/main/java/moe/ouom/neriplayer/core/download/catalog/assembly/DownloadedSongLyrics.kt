package moe.ouom.neriplayer.core.download.catalog.assembly

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage.DownloadedAudioMetadata
import moe.ouom.neriplayer.core.download.catalog.shouldInspectDownloadedLocalLyrics
import moe.ouom.neriplayer.core.download.policy.resolveDownloadedLyricOverride

internal data class DownloadedSongLyricContent(
    val fileLyric: String? = null,
    val indexedLyric: String? = null,
    val fileTranslatedLyric: String? = null,
    val indexedTranslatedLyric: String? = null,
    val fileRomanizedLyric: String? = null,
    val indexedRomanizedLyric: String? = null
) {
    fun needsLocalFallback(loadContents: Boolean, metadata: DownloadedAudioMetadata): Boolean =
        shouldInspectDownloadedLocalLyrics(
            loadLyricContents = loadContents,
            fileLyric = fileLyric,
            fileTranslatedLyric = fileTranslatedLyric,
            fileRomanizedLyric = fileRomanizedLyric,
            matchedLyric = metadata.matchedLyric,
            originalLyric = metadata.originalLyric,
            matchedTranslatedLyric = metadata.matchedTranslatedLyric,
            originalTranslatedLyric = metadata.originalTranslatedLyric,
            matchedRomanizedLyric = metadata.matchedRomanizedLyric,
            originalRomanizedLyric = metadata.originalRomanizedLyric,
            indexedLyric = indexedLyric,
            indexedTranslatedLyric = indexedTranslatedLyric,
            indexedRomanizedLyric = indexedRomanizedLyric
        )

    fun resolve(
        loadContents: Boolean,
        metadata: DownloadedAudioMetadata,
        localLyric: () -> String?
    ): DownloadedSongLyrics {
        if (!loadContents) {
            return DownloadedSongLyrics(
                metadata.matchedLyric,
                metadata.matchedTranslatedLyric,
                metadata.matchedRomanizedLyric
            )
        }
        return DownloadedSongLyrics(
            original = resolveDownloadedLyricOverride(
                fileLyric, metadata.matchedLyric, metadata.originalLyric,
                localLyric(), indexedLyric
            ),
            translated = resolveDownloadedLyricOverride(
                fileTranslatedLyric, metadata.matchedTranslatedLyric,
                metadata.originalTranslatedLyric, null, indexedTranslatedLyric
            ),
            romanized = resolveDownloadedLyricOverride(
                fileRomanizedLyric, metadata.matchedRomanizedLyric,
                metadata.originalRomanizedLyric, null, indexedRomanizedLyric
            )
        )
    }
}

internal data class DownloadedSongLyrics(
    val original: String?,
    val translated: String?,
    val romanized: String?
)
