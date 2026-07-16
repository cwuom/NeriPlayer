package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongDetails
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.data.model.SongItem

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

internal fun shouldAutoMatchExternalLyrics(
    song: SongItem,
    isYouTubeMusicTrack: Boolean
): Boolean {
    if (!isYouTubeMusicTrack) return false
    if (song.matchedSongId != null || !song.matchedLyric.isNullOrEmpty()) return false
    return song.customName == null && song.customArtist == null && song.customCoverUrl == null
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

internal fun SongSearchInfo.toBasicSongDetails(): SongDetails {
    return SongDetails(
        id = id,
        songName = songName,
        singer = singer,
        album = albumName.orEmpty(),
        coverUrl = coverUrl,
        lyric = null,
        translatedLyric = null
    )
}

internal fun SongDetails.hasUsableLyrics(): Boolean {
    return !lyric.isNullOrBlank() || !translatedLyric.isNullOrBlank()
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
    useCustomOverride: Boolean,
    preserveExistingMatchedLyrics: Boolean = false
): SongItem {
    val originalName = originalSong.originalName ?: originalSong.name
    val originalArtist = originalSong.originalArtist ?: originalSong.artist
    val originalCoverUrl = originalSong.originalCoverUrl ?: originalSong.coverUrl
    val hasExistingMatchedLyrics = originalSong.matchedLyric != null ||
        originalSong.matchedTranslatedLyric != null
    val keepExistingMatch = preserveExistingMatchedLyrics && hasExistingMatchedLyrics
    val resolvedLyric = if (keepExistingMatch) originalSong.matchedLyric else lyric
    val resolvedTranslatedLyric = if (keepExistingMatch) {
        originalSong.matchedTranslatedLyric
    } else {
        translatedLyric
    }
    val resolvedMatchedSource = if (keepExistingMatch) {
        originalSong.matchedLyricSource ?: matchedSource
    } else {
        matchedSource
    }
    val resolvedMatchedSongId = if (keepExistingMatch) {
        originalSong.matchedSongId ?: matchedSongId
    } else {
        matchedSongId
    }

    return if (useCustomOverride) {
        originalSong.copy(
            matchedLyric = resolvedLyric,
            matchedTranslatedLyric = resolvedTranslatedLyric,
            matchedLyricSource = resolvedMatchedSource,
            matchedSongId = resolvedMatchedSongId,
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
            matchedLyric = resolvedLyric,
            matchedTranslatedLyric = resolvedTranslatedLyric,
            matchedLyricSource = resolvedMatchedSource,
            matchedSongId = resolvedMatchedSongId,
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
