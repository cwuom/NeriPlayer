package moe.ouom.neriplayer.core.download.catalog.projection

import moe.ouom.neriplayer.data.model.download.DownloadedSong
import moe.ouom.neriplayer.core.download.policy.withRecoveredRemoteSourceStableKey
import moe.ouom.neriplayer.data.model.SongItem

internal fun projectDownloadedSongEdits(
    existing: DownloadedSong,
    updatedSong: SongItem
): DownloadedSong {
    val source = DownloadedSongSourceProjection(existing, updatedSong)
    val originals = DownloadedSongOriginalValues(existing, updatedSong, source.preservesExistingSource)
    val local = DownloadedSongLocalEdits(existing, updatedSong)
    return existing.copy(
        id = source.id,
        name = updatedSong.name,
        artist = updatedSong.artist,
        album = local.album,
        coverPath = local.coverPath,
        coverUrl = local.coverUrl,
        matchedLyric = updatedSong.matchedLyric,
        matchedTranslatedLyric = updatedSong.matchedTranslatedLyric,
        matchedRomanizedLyric = updatedSong.matchedRomanizedLyric,
        matchedLyricSource = updatedSong.matchedLyricSource?.name,
        matchedSongId = updatedSong.matchedSongId,
        userLyricOffsetMs = updatedSong.userLyricOffsetMs,
        customCoverUrl = local.customCoverUrl,
        customName = updatedSong.customName,
        customArtist = updatedSong.customArtist,
        originalName = originals.name,
        originalArtist = originals.artist,
        originalCoverUrl = originals.coverUrl,
        originalLyric = originals.lyric,
        originalTranslatedLyric = originals.translatedLyric,
        originalRomanizedLyric = originals.romanizedLyric,
        mediaUri = local.mediaUri,
        localFileName = local.fileName,
        durationMs = local.durationMs,
        stableKey = source.stableKey,
        sourceIdentityAlbum = source.identityAlbum,
        sourceMediaUri = source.mediaUri,
        sourceChannelId = source.channelId,
        sourceAudioId = source.audioId,
        sourceSubAudioId = source.subAudioId,
        sourcePlaylistContextId = source.playlistContextId
    ).withRecoveredRemoteSourceStableKey()
}
