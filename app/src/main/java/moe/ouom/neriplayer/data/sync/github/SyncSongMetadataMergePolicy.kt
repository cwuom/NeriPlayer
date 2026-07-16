package moe.ouom.neriplayer.data.sync.github

internal object SyncSongMetadataMergePolicy {
    fun canonicalPayloadKey(song: SyncSong): String {
        return listOf(
            song.id.toString(),
            song.name,
            song.artist,
            song.album,
            song.albumId.toString(),
            song.durationMs.toString(),
            song.coverUrl.orEmpty(),
            song.mediaUri.orEmpty(),
            song.addedAt.toString(),
            song.matchedLyric.orEmpty(),
            song.matchedTranslatedLyric.orEmpty(),
            song.matchedLyricSource.orEmpty(),
            song.matchedSongId.orEmpty(),
            song.userLyricOffsetMs.toString(),
            song.customCoverUrl.orEmpty(),
            song.customName.orEmpty(),
            song.customArtist.orEmpty(),
            song.originalName.orEmpty(),
            song.originalArtist.orEmpty(),
            song.originalCoverUrl.orEmpty(),
            song.originalLyric.orEmpty(),
            song.originalTranslatedLyric.orEmpty(),
            song.channelId.orEmpty(),
            song.audioId.orEmpty(),
            song.subAudioId.orEmpty(),
            song.playlistContextId.orEmpty()
        ).joinToString(separator = "") { value -> "${value.length}:$value" }
    }
}
