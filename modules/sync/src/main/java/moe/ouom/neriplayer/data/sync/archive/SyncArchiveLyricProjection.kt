package moe.ouom.neriplayer.data.sync.archive

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.hasSyncLyricText
import moe.ouom.neriplayer.data.sync.identity.stableKey
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

internal object SyncArchiveLyricProjection {
    fun reference(song: SyncSong): SyncSong {
        val normalized = SyncSongLyricMergePolicy.normalize(song)
        if (!normalized.hasSyncLyricText()) return normalized
        return normalized.copy(matchedLyric = null, matchedTranslatedLyric = null, matchedRomanizedLyric = null,
            originalLyric = null, originalTranslatedLyric = null, originalRomanizedLyric = null)
    }

    fun restore(data: SyncData, beforeNormalization: (SyncData) -> Unit = {}): SyncData {
        val overrides = SyncSongLyricMergePolicy.mergeOverrides(data.lyricOverrides).associateBy { it.stableKey() }
        val songs = data.playlists.asSequence().flatMap { it.songs.asSequence() } +
            data.favoritePlaylists.asSequence().flatMap { it.songs.asSequence() } +
            data.recentPlays.asSequence().map { it.song }
        songs.forEach { validateReference(it, overrides) }
        beforeNormalization(data)
        return SyncSongLyricMergePolicy.converge(data)
    }

    private fun validateReference(song: SyncSong, overrides: Map<String, SyncSong>) {
        if (song.lyricSyncEdited == null || (song.lyricSyncRevision == 0L && song.lyricSyncEdited != true)) return
        val override = requireNotNull(overrides[song.stableKey()]) { "Sync lyric reference has no committed override" }
        require(override.lyricSyncRevision >= song.lyricSyncRevision) { "Sync lyric reference has no committed override" }
    }
}
