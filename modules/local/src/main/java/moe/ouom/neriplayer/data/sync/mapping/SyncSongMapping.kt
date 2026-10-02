package moe.ouom.neriplayer.data.sync.mapping

import moe.ouom.neriplayer.data.model.sync.CURRENT_SYNC_METADATA_VERSION
import moe.ouom.neriplayer.data.model.sync.normalizedSyncCausalTokens
import moe.ouom.neriplayer.data.model.sync.SyncSong
import android.content.Context
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.identity.toSyncableRemoteSongOrNull
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

fun SyncSong.Companion.fromSongItemOrNull(song: SongItem, context: Context? = null, optimizeLegacyLyrics: Boolean = false): SyncSong? {
    return song
        .toSyncableRemoteSongOrNull(context)
        ?.let { syncableSong -> fromSongItem(syncableSong, context, optimizeLegacyLyrics) }
}

fun SyncSong.Companion.fromSongItem(song: SongItem, context: Context? = null, optimizeLegacyLyrics: Boolean = false): SyncSong {
    val mapper = context?.let { CoverUrlMapper.getInstance(it) }
    val syncCoverUrl = sanitizeCoverUrlForSync(song.coverUrl, mapper)
    val syncCustomCoverUrl = sanitizeCoverUrlForSync(song.customCoverUrl, mapper)
    val syncOriginalCoverUrl = sanitizeCoverUrlForSync(song.originalCoverUrl, mapper)
    val raw = SyncSong(
        id = song.id,
        name = song.name,
        artist = song.artist,
        album = song.album,
        albumId = song.albumId,
        durationMs = song.durationMs,
        coverUrl = syncCoverUrl,
        mediaUri = LocalSongSupport.sanitizeMediaUriForSync(song.mediaUri),
        addedAt = song.addedAt.coerceAtLeast(0L),
        matchedLyric = song.matchedLyric,
        matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric,
        originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric,
        originalRomanizedLyric = song.originalRomanizedLyric,
        lyricSyncEdited = song.lyricSyncEdited,
        lyricSyncRevision = song.lyricSyncRevision,
        matchedLyricSource = song.matchedLyricSource?.name,
        matchedSongId = song.matchedSongId,
        userLyricOffsetMs = song.userLyricOffsetMs,
        customCoverUrl = syncCustomCoverUrl,
        customName = song.customName,
        customArtist = song.customArtist,
        originalName = song.originalName,
        originalArtist = song.originalArtist,
        originalCoverUrl = syncOriginalCoverUrl,
        channelId = song.channelId,
        audioId = song.audioId,
        subAudioId = song.subAudioId,
        playlistContextId = song.playlistContextId,
        syncMembershipTokens = song.syncMembershipTokens.normalizedSyncCausalTokens(),
        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
    )
    return SyncSongLyricMergePolicy.prepareLegacy(raw, optimizeLegacyLyrics)
}

fun SyncSong.toSongItem(existing: SongItem? = null): SongItem {
    val restored = SongItem(
        id = id,
        name = name,
        artist = artist,
        album = album,
        albumId = albumId,
        durationMs = durationMs,
        coverUrl = coverUrl,
        mediaUri = LocalSongSupport.sanitizeMediaUriForSync(mediaUri),
        matchedLyric = matchedLyric,
        matchedTranslatedLyric = matchedTranslatedLyric,
        matchedRomanizedLyric = matchedRomanizedLyric,
        lyricSyncRevision = lyricSyncRevision,
        lyricSyncEdited = lyricSyncEdited,
        matchedLyricSource = matchedLyricSource?.let {
            try { MusicPlatform.valueOf(it) } catch (e: Exception) { null }
        },
        matchedSongId = matchedSongId,
        userLyricOffsetMs = userLyricOffsetMs,
        customCoverUrl = customCoverUrl,
        customName = customName,
        customArtist = customArtist,
        originalName = originalName,
        originalArtist = originalArtist,
        originalCoverUrl = originalCoverUrl,
        originalLyric = originalLyric,
        originalTranslatedLyric = originalTranslatedLyric,
        originalRomanizedLyric = originalRomanizedLyric,
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        addedAt = addedAt,
        syncMembershipTokens = syncMembershipTokens.normalizedSyncCausalTokens()
    )
    return restoreSyncLyrics(restored, existing)
}

private fun restoreSyncLyrics(restored: SongItem, existing: SongItem?): SongItem {
    if (existing == null) return restored
    val localRevision = if (existing.lyricSyncEdited == null) 0L else existing.lyricSyncRevision
    if (localRevision > restored.lyricSyncRevision) {
        return preserveNewerLocalLyrics(restored, existing)
    }
    return applyRemoteLyrics(restored, existing)
}

private fun preserveNewerLocalLyrics(restored: SongItem, existing: SongItem): SongItem = restored.copy(
    matchedLyric = existing.matchedLyric,
    matchedTranslatedLyric = existing.matchedTranslatedLyric,
    matchedRomanizedLyric = existing.matchedRomanizedLyric,
    matchedLyricSource = existing.matchedLyricSource,
    matchedSongId = existing.matchedSongId,
    originalLyric = existing.originalLyric,
    originalTranslatedLyric = existing.originalTranslatedLyric,
    originalRomanizedLyric = existing.originalRomanizedLyric,
    lyricSyncRevision = existing.lyricSyncRevision,
    lyricSyncEdited = existing.lyricSyncEdited
)

private fun applyRemoteLyrics(restored: SongItem, existing: SongItem): SongItem {
    val knownCache = existing.lyricSyncEdited == false
    val original = preservedOriginalLyric(existing.originalLyric, restored.originalLyric, existing.matchedLyric, knownCache)
    val translated = preservedOriginalLyric(existing.originalTranslatedLyric, restored.originalTranslatedLyric, existing.matchedTranslatedLyric, knownCache)
    val romanized = preservedOriginalLyric(existing.originalRomanizedLyric, restored.originalRomanizedLyric, existing.matchedRomanizedLyric, knownCache)
    if (restored.lyricSyncEdited == false) {
        if (restored.lyricSyncRevision > 0L) {
            return restored.copy(
                matchedLyric = original, matchedTranslatedLyric = translated, matchedRomanizedLyric = romanized,
                originalLyric = original, originalTranslatedLyric = translated, originalRomanizedLyric = romanized
            )
        }
        return restored.copy(
            matchedLyric = existing.matchedLyric,
            matchedTranslatedLyric = existing.matchedTranslatedLyric,
            matchedRomanizedLyric = existing.matchedRomanizedLyric,
            matchedLyricSource = existing.matchedLyricSource,
            matchedSongId = existing.matchedSongId,
            lyricSyncEdited = existing.lyricSyncEdited,
            originalLyric = original, originalTranslatedLyric = translated, originalRomanizedLyric = romanized
        )
    }
    return restored.copy(
        originalLyric = original, originalTranslatedLyric = translated, originalRomanizedLyric = romanized
    )
}

private fun preservedOriginalLyric(localOriginal: String?, remoteOriginal: String?, localMatched: String?, knownCache: Boolean): String? =
    remoteOriginal ?: localOriginal ?: if (knownCache) localMatched else null
