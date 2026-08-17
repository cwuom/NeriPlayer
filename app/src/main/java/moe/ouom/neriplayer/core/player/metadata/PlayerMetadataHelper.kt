package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongDetails
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.data.model.SongItem

internal fun SongItem.withUpdatedLyricsPreservingOriginal(
    newLyrics: String? = matchedLyric,
    newTranslatedLyric: String? = matchedTranslatedLyric,
    newRomanizedLyric: String? = matchedRomanizedLyric
): SongItem {
    return copy(
        matchedLyric = newLyrics,
        matchedTranslatedLyric = newTranslatedLyric,
        matchedRomanizedLyric = newRomanizedLyric,
        originalLyric = originalLyric ?: matchedLyric,
        originalTranslatedLyric = originalTranslatedLyric ?: matchedTranslatedLyric,
        originalRomanizedLyric = originalRomanizedLyric ?: matchedRomanizedLyric
    )
}

internal fun shouldAutoMatchExternalLyrics(
    song: SongItem,
    isYouTubeMusicTrack: Boolean
): Boolean {
    if (!isYouTubeMusicTrack) return false
    if (song.matchedSongId != null || !song.matchedLyric.isNullOrEmpty()) return false
    if (song.customName != null || song.customArtist != null || song.customCoverUrl != null) {
        return false
    }
    // YouTube lyrics are resolved through LRCLIB instead of cross-platform metadata replacement
    return false
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

internal fun shouldWriteLocalCoverMetadata(
    restoreBaseCover: Boolean,
    nextCustomCover: String?,
    previousCustomCover: String?
): Boolean {
    return restoreBaseCover ||
        nextCustomCover != previousCustomCover ||
        !nextCustomCover.isNullOrBlank()
}

internal fun resolveLocalCoverWriteReference(
    restoreBaseCover: Boolean,
    requestedCoverReference: String?,
    restoredBaseCoverReference: String?
): String? {
    val reference = if (restoreBaseCover) {
        restoredBaseCoverReference
    } else {
        requestedCoverReference
    }
    return reference?.trim()?.takeIf(String::isNotBlank)
}

internal fun resolveRestoredBaseCoverUrl(
    originalCoverUrl: String?,
    baseCoverUrl: String?,
    currentCustomCoverUrl: String?
): String? {
    val customCover = currentCustomCoverUrl?.trim()?.takeIf { it.isNotBlank() }
    return originalCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != customCover }
        ?: baseCoverUrl
            ?.trim()
            ?.takeIf { it.isNotBlank() && it != customCover }
}

internal enum class LocalMetadataWritePlaybackAction {
    NONE,
    RELEASE_ONLY,
    RELEASE_AND_RESUME
}

internal fun resolveLocalMetadataWritePlaybackAction(
    isTargetCurrentSong: Boolean,
    hasLoadedMedia: Boolean,
    shouldResumePlayback: Boolean
): LocalMetadataWritePlaybackAction {
    if (!isTargetCurrentSong || !hasLoadedMedia) {
        return LocalMetadataWritePlaybackAction.NONE
    }
    return if (shouldResumePlayback) {
        LocalMetadataWritePlaybackAction.RELEASE_AND_RESUME
    } else {
        LocalMetadataWritePlaybackAction.RELEASE_ONLY
    }
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
