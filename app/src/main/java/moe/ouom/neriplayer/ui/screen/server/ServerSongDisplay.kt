package moe.ouom.neriplayer.ui.screen.server

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.server.ServerSongRef

/** Overlay user metadata without replacing the server's transport or resource identity. */
internal fun resolveServerDisplaySongs(
    songs: List<SongItem>,
    savedSongs: List<SongItem>,
    queuedSongs: List<SongItem>,
    currentSong: SongItem?
): List<SongItem> {
    val metadata = mutableMapOf<ServerSongRef, SongItem>()
    for (saved in savedSongs) {
        ServerSongRef.from(saved)?.let { metadata.putIfAbsent(it, saved) }
    }
    for (queued in queuedSongs) {
        ServerSongRef.from(queued)?.let {
            if (it !in metadata || queued.hasUserMetadata()) metadata[it] = queued
        }
    }
    currentSong?.let { current -> ServerSongRef.from(current)?.let {
        if (it !in metadata || current.hasUserMetadata()) metadata[it] = current
    } }
    return songs.map { source ->
        val saved = metadata[ServerSongRef.from(source)] ?: return@map source
        source.copy(
            customName = saved.customName,
            customArtist = saved.customArtist,
            customCoverUrl = saved.customCoverUrl,
            originalName = saved.originalName,
            originalArtist = saved.originalArtist,
            originalCoverUrl = saved.originalCoverUrl,
            matchedLyric = saved.matchedLyric,
            matchedTranslatedLyric = saved.matchedTranslatedLyric,
            matchedRomanizedLyric = saved.matchedRomanizedLyric,
            matchedLyricSource = saved.matchedLyricSource,
            matchedSongId = saved.matchedSongId,
            originalLyric = saved.originalLyric,
            originalTranslatedLyric = saved.originalTranslatedLyric,
            originalRomanizedLyric = saved.originalRomanizedLyric,
            userLyricOffsetMs = saved.userLyricOffsetMs,
            lyricSyncRevision = saved.lyricSyncRevision,
            lyricSyncEdited = saved.lyricSyncEdited
        )
    }
}

private fun SongItem.hasUserMetadata(): Boolean = customName != null || customArtist != null ||
    customCoverUrl != null || originalName != null || originalArtist != null || originalCoverUrl != null ||
    lyricSyncEdited == true || userLyricOffsetMs != 0L
