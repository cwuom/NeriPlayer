package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong

internal fun fillLegacySyncSongMetadata(selected: SyncSong, candidates: List<SyncSong>): SyncSong {
    val fields = LegacySyncMetadataFields(candidates)
    return selected.copy(
        id = fields.nonZero(selected.id, SyncSong::id),
        name = fields.requiredText(selected.name, SyncSong::name),
        artist = fields.requiredText(selected.artist, SyncSong::artist),
        album = fields.requiredText(selected.album, SyncSong::album),
        albumId = fields.nonZero(selected.albumId, SyncSong::albumId),
        durationMs = fields.positive(selected.durationMs, SyncSong::durationMs),
        coverUrl = fields.text(selected.coverUrl, SyncSong::coverUrl),
        mediaUri = fields.text(selected.mediaUri, SyncSong::mediaUri),
        matchedLyric = fields.text(selected.matchedLyric, SyncSong::matchedLyric),
        matchedTranslatedLyric = fields.text(selected.matchedTranslatedLyric, SyncSong::matchedTranslatedLyric),
        matchedLyricSource = fields.text(selected.matchedLyricSource, SyncSong::matchedLyricSource),
        matchedSongId = fields.text(selected.matchedSongId, SyncSong::matchedSongId),
        userLyricOffsetMs = fields.nonZero(selected.userLyricOffsetMs, SyncSong::userLyricOffsetMs),
        customCoverUrl = fields.text(selected.customCoverUrl, SyncSong::customCoverUrl),
        customName = fields.text(selected.customName, SyncSong::customName),
        customArtist = fields.text(selected.customArtist, SyncSong::customArtist),
        originalName = fields.text(selected.originalName, SyncSong::originalName),
        originalArtist = fields.text(selected.originalArtist, SyncSong::originalArtist),
        originalCoverUrl = fields.text(selected.originalCoverUrl, SyncSong::originalCoverUrl),
        originalLyric = fields.text(selected.originalLyric, SyncSong::originalLyric),
        originalTranslatedLyric = fields.text(selected.originalTranslatedLyric, SyncSong::originalTranslatedLyric),
        channelId = fields.text(selected.channelId, SyncSong::channelId),
        audioId = fields.text(selected.audioId, SyncSong::audioId),
        subAudioId = fields.text(selected.subAudioId, SyncSong::subAudioId),
        playlistContextId = fields.text(selected.playlistContextId, SyncSong::playlistContextId)
    )
}
