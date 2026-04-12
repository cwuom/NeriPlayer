package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem

internal fun SongItem.withUpdatedLyricsPreservingOriginal(
    newLyrics: String? = matchedLyric,
    newTranslatedLyric: String? = matchedTranslatedLyric
): SongItem {
    return copy(
        matchedLyric = newLyrics,
        matchedTranslatedLyric = newTranslatedLyric,
        originalLyric = originalLyric ?: matchedLyric,
        originalTranslatedLyric = originalTranslatedLyric ?: matchedTranslatedLyric
    )
}

internal fun normalizeCustomMetadataValue(
    desiredValue: String?,
    baseValue: String?
): String? {
    val normalizedDesired = desiredValue?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null
    return normalizedDesired.takeIf { it != baseValue }
}

internal fun applyManualSearchMetadata(
    originalSong: SongItem,
    songName: String,
    singer: String,
    coverUrl: String?,
    lyric: String?,
    translatedLyric: String?,
    matchedSource: MusicPlatform,
    matchedSongId: String,
    useCustomOverride: Boolean
): SongItem {
    val originalName = originalSong.originalName ?: originalSong.name
    val originalArtist = originalSong.originalArtist ?: originalSong.artist
    val originalCoverUrl = originalSong.originalCoverUrl ?: originalSong.coverUrl

    return if (useCustomOverride) {
        originalSong.copy(
            matchedLyric = lyric,
            matchedTranslatedLyric = translatedLyric,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customCoverUrl = normalizeCustomMetadataValue(coverUrl, originalSong.coverUrl),
            customName = normalizeCustomMetadataValue(songName, originalSong.name),
            customArtist = normalizeCustomMetadataValue(singer, originalSong.artist),
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
        )
    } else {
        originalSong.copy(
            name = songName,
            artist = singer,
            coverUrl = coverUrl,
            matchedLyric = lyric,
            matchedTranslatedLyric = translatedLyric,
            matchedLyricSource = matchedSource,
            matchedSongId = matchedSongId,
            customCoverUrl = null,
            customName = null,
            customArtist = null,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric
        )
    }
}
