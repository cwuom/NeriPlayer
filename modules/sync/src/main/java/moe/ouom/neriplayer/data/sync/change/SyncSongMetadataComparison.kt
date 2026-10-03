package moe.ouom.neriplayer.data.sync.change

import moe.ouom.neriplayer.data.model.sync.SyncSong

internal object SyncSongMetadataComparison {
    fun same(a: SyncSong, b: SyncSong): Boolean =
        sameTrack(a, b) && sameLyrics(a, b) && sameCustomMetadata(a, b) && sameSource(a, b)

    private fun sameTrack(a: SyncSong, b: SyncSong): Boolean =
        a.name == b.name && a.artist == b.artist && a.album == b.album &&
            a.albumId == b.albumId && a.durationMs == b.durationMs &&
            a.coverUrl == b.coverUrl && a.mediaUri == b.mediaUri && a.addedAt == b.addedAt

    private fun sameLyrics(a: SyncSong, b: SyncSong): Boolean =
        a.matchedLyric == b.matchedLyric && a.matchedTranslatedLyric == b.matchedTranslatedLyric &&
            a.matchedLyricSource == b.matchedLyricSource && a.matchedSongId == b.matchedSongId &&
            a.userLyricOffsetMs == b.userLyricOffsetMs && a.matchedRomanizedLyric == b.matchedRomanizedLyric &&
            a.lyricSyncRevision == b.lyricSyncRevision && a.lyricSyncEdited == b.lyricSyncEdited

    private fun sameCustomMetadata(a: SyncSong, b: SyncSong): Boolean =
        a.customCoverUrl == b.customCoverUrl && a.customName == b.customName &&
            a.customArtist == b.customArtist && a.originalName == b.originalName &&
            a.originalArtist == b.originalArtist && a.originalCoverUrl == b.originalCoverUrl &&
            a.originalLyric == b.originalLyric && a.originalTranslatedLyric == b.originalTranslatedLyric

    private fun sameSource(a: SyncSong, b: SyncSong): Boolean =
        a.channelId == b.channelId && a.audioId == b.audioId && a.subAudioId == b.subAudioId &&
            a.playlistContextId == b.playlistContextId && a.syncMetadataVersion == b.syncMetadataVersion &&
            a.syncMembershipTokens.toSet() == b.syncMembershipTokens.toSet()
}
