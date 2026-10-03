package moe.ouom.neriplayer.core.player.metadata

import moe.ouom.neriplayer.data.local.media.isMediaStoreCoverReference
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.model.music.SongDetails
import moe.ouom.neriplayer.data.model.music.SongSearchInfo
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sync.nextLyricSyncRevision

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
    newRomanizedLyric: String? = matchedRomanizedLyric,
    userEdited: Boolean = true,
    revision: Long = if (userEdited) nextLyricSyncRevision(lyricSyncRevision, System.currentTimeMillis()) else lyricSyncRevision
): SongItem {
    return copy(
        matchedLyric = newLyrics,
        matchedTranslatedLyric = newTranslatedLyric,
        matchedRomanizedLyric = newRomanizedLyric,
        lyricSyncEdited = userEdited,
        lyricSyncRevision = revision,
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
    return reference?.trim()?.takeIf(String::isNotBlank)?.takeUnless(::isMediaStoreCoverReference)
}

internal fun shouldMaterializeRemoteLocalCover(
    isLocalSong: Boolean,
    requestedCoverReference: String?,
    restoreBaseCover: Boolean,
    persistManualRemoteCover: Boolean
): Boolean {
    return isLocalSong &&
        (restoreBaseCover || persistManualRemoteCover) &&
        requestedCoverReference?.trim()?.isRemoteCoverReference() == true
}

internal fun resolveRestoredBaseCoverUrl(
    originalCoverUrl: String?,
    baseCoverUrl: String?,
    currentCustomCoverUrl: String?,
    preferredLocalCoverUrl: String? = null,
    requestedRestoreCoverUrl: String? = null,
    localOnly: Boolean = false
): String? {
    val requestedRestoreCover = requestedRestoreCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.takeUnless(::isMediaStoreCoverReference)
    val preferredLocalCover = preferredLocalCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && !it.isRemoteCoverReference() }
        ?.takeUnless(::isMediaStoreCoverReference)
    val customCover = currentCustomCoverUrl?.trim()?.takeIf { it.isNotBlank() }
        ?.takeUnless(::isMediaStoreCoverReference)
    val originalCover = originalCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != customCover }
        ?.takeUnless(::isMediaStoreCoverReference)
    val baseCover = baseCoverUrl
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != customCover }
        ?.takeUnless(::isMediaStoreCoverReference)
    if (localOnly) {
        return requestedRestoreCover
            ?.takeUnless(String::isRemoteCoverReference)
            ?: preferredLocalCover ?: originalCover
            ?.takeUnless(String::isRemoteCoverReference)
            ?: baseCover?.takeUnless(String::isRemoteCoverReference)
    }
    return requestedRestoreCover ?: preferredLocalCover ?: originalCover ?: baseCover ?: customCover.takeIf {
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

internal fun resolveLocalMetadataWritePlaybackAction(
    isCurrentSong: Boolean,
    hasActiveMedia: Boolean,
    shouldResumePlayback: Boolean
): LocalMetadataWritePlaybackAction = when {
    !isCurrentSong || !hasActiveMedia -> LocalMetadataWritePlaybackAction.NONE
    shouldResumePlayback -> LocalMetadataWritePlaybackAction.RELEASE_AND_RESUME
    else -> LocalMetadataWritePlaybackAction.RELEASE_ONLY
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
    preserveExistingMatchedLyrics: Boolean = false,
    lyricRevision: Long = nextLyricSyncRevision(originalSong.lyricSyncRevision, System.currentTimeMillis())
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
    val resolvedRomanizedLyric = if (keepExistingMatch) originalSong.matchedRomanizedLyric else ""
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

    val resolvedLyricRevision = if (keepExistingMatch) originalSong.lyricSyncRevision else lyricRevision
    val lyricEdited = if (keepExistingMatch) originalSong.lyricSyncEdited else true
    return if (useCustomOverride) {
        originalSong.copy(
            matchedLyric = resolvedLyric,
            matchedTranslatedLyric = resolvedTranslatedLyric,
            matchedRomanizedLyric = resolvedRomanizedLyric,
            lyricSyncEdited = lyricEdited,
            lyricSyncRevision = resolvedLyricRevision,
            matchedLyricSource = resolvedMatchedSource,
            matchedSongId = resolvedMatchedSongId,
            customCoverUrl = normalizeCustomMetadataValue(coverUrl, originalSong.coverUrl),
            customName = normalizeCustomMetadataValue(songName, originalSong.name),
            customArtist = normalizeCustomMetadataValue(singer, originalSong.artist),
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric,
            originalRomanizedLyric = originalSong.originalRomanizedLyric ?: originalSong.matchedRomanizedLyric
        )
    } else {
        originalSong.copy(
            name = songName,
            artist = singer,
            coverUrl = coverUrl,
            matchedLyric = resolvedLyric,
            matchedTranslatedLyric = resolvedTranslatedLyric,
            matchedRomanizedLyric = resolvedRomanizedLyric,
            lyricSyncEdited = lyricEdited,
            lyricSyncRevision = resolvedLyricRevision,
            matchedLyricSource = resolvedMatchedSource,
            matchedSongId = resolvedMatchedSongId,
            customCoverUrl = null,
            customName = null,
            customArtist = null,
            originalName = originalName,
            originalArtist = originalArtist,
            originalCoverUrl = originalCoverUrl,
            originalLyric = originalSong.originalLyric ?: originalSong.matchedLyric,
            originalTranslatedLyric = originalSong.originalTranslatedLyric ?: originalSong.matchedTranslatedLyric,
            originalRomanizedLyric = originalSong.originalRomanizedLyric ?: originalSong.matchedRomanizedLyric
        )
    }
}
