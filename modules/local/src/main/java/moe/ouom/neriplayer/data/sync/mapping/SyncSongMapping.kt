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

fun SyncSong.Companion.fromSongItemOrNull(song: SongItem, context: Context? = null): SyncSong? {
    return song
        .toSyncableRemoteSongOrNull(context)
        ?.let { syncableSong -> fromSongItem(syncableSong, context) }
}

fun SyncSong.Companion.fromSongItem(song: SongItem, context: Context? = null): SyncSong {
    val mapper = context?.let { CoverUrlMapper.getInstance(it) }
    val syncCoverUrl = sanitizeCoverUrlForSync(song.coverUrl, mapper)
    val syncCustomCoverUrl = sanitizeCoverUrlForSync(song.customCoverUrl, mapper)
    val syncOriginalCoverUrl = sanitizeCoverUrlForSync(song.originalCoverUrl, mapper)

    return SyncSong(
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
        matchedLyricSource = song.matchedLyricSource?.name,
        matchedSongId = song.matchedSongId,
        userLyricOffsetMs = song.userLyricOffsetMs,
        customCoverUrl = syncCustomCoverUrl,
        customName = song.customName,
        customArtist = song.customArtist,
        originalName = song.originalName,
        originalArtist = song.originalArtist,
        originalCoverUrl = syncOriginalCoverUrl,
        originalLyric = song.originalLyric,
        originalTranslatedLyric = song.originalTranslatedLyric,
        channelId = song.channelId,
        audioId = song.audioId,
        subAudioId = song.subAudioId,
        playlistContextId = song.playlistContextId,
        syncMembershipTokens = song.syncMembershipTokens.normalizedSyncCausalTokens(),
        syncMetadataVersion = CURRENT_SYNC_METADATA_VERSION
    )
}

fun SyncSong.toSongItem(): SongItem {
    return SongItem(
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
        channelId = channelId,
        audioId = audioId,
        subAudioId = subAudioId,
        playlistContextId = playlistContextId,
        addedAt = addedAt,
        syncMembershipTokens = syncMembershipTokens.normalizedSyncCausalTokens()
    )
}
