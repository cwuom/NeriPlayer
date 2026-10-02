package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong

internal fun syncSongPayloadKey(song: SyncSong): String = listOf(
    song.id.toString(), song.name, song.artist, song.album, song.albumId.toString(), song.durationMs.toString(),
    song.coverUrl, song.mediaUri, song.addedAt.toString(), song.matchedLyric, song.matchedTranslatedLyric,
    song.matchedLyricSource, song.matchedSongId, song.userLyricOffsetMs.toString(), song.customCoverUrl,
    song.customName, song.customArtist, song.originalName, song.originalArtist, song.originalCoverUrl,
    song.originalLyric, song.originalTranslatedLyric, song.channelId, song.audioId, song.subAudioId,
    song.playlistContextId, song.syncMetadataVersion.toString(), song.lyricSyncRevision.toString(),
    song.lyricSyncEdited?.toString(), song.matchedRomanizedLyric
).joinToString(separator = "") { raw ->
    val value = raw.orEmpty()
    "${value.length}:$value"
}
