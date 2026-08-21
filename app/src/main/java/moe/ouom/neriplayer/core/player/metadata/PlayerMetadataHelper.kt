package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.core.api.search.MusicPlatform
import moe.ouom.neriplayer.core.api.search.SongDetails
import moe.ouom.neriplayer.core.api.search.SongSearchInfo
import moe.ouom.neriplayer.data.model.SongItem

internal fun shouldSkipSongMetadataMutation(
    currentSong: SongItem,
    updatedSong: SongItem,
    writeLyrics: Boolean
): Boolean {
    return !writeLyrics && currentSong == updatedSong
}

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
        nextCustomCover != previousCustomCover
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
    currentCustomCoverUrl: String?,
    preferredLocalCoverUrl: String? = null,
    localOnly: Boolean = false
): String? {
    val preferredLocalCover = preferredLocalCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.isRemoteCoverReference() }
    val customCover = currentCustomCoverUrl?.trim()?.takeIf { it.isNotBlank() }
    val originalCover = originalCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != customCover }
    val baseCover = baseCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != customCover }
    if (localOnly) {
        return preferredLocalCover ?: originalCover
            ?.takeUnless(String::isRemoteCoverReference)
            ?: baseCover?.takeUnless(String::isRemoteCoverReference)
    }
    return preferredLocalCover ?: originalCover ?: baseCover ?: customCover.takeIf {
        originalCover == null && baseCover == null
    }
}

private fun String.isRemoteCoverReference(): Boolean {
    return startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true)
}

internal enum class LocalMetadataWritePlaybackAction {
    NONE,
    RELEASE_ONLY,
    RELEASE_AND_RESUME
}

internal fun resolveLocalMetadataWritePlaybackAction(): LocalMetadataWritePlaybackAction =
    LocalMetadataWritePlaybackAction.NONE

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
