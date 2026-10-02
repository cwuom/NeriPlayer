package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.identity.identity

object SyncSongLyricMergePolicy {
    fun converge(data: SyncData): SyncData {
        val overrides = collectOverrides(data)
        val latestByIdentity = overrides.associateBy { it.identity().stableKey() }
        val restore: (SyncSong) -> SyncSong = if (latestByIdentity.isEmpty()) ::normalize else { song ->
            val latest = latestByIdentity[song.identity().stableKey()]
            if (latest == null) normalize(song) else merge(song, listOf(latest))
        }
        val playlists = mapChanged(data.playlists) { playlist ->
            val songs = mapChanged(playlist.songs, restore)
            if (songs === playlist.songs) playlist else playlist.copy(songs = songs)
        }
        val favorites = mapChanged(data.favoritePlaylists) { playlist ->
            val songs = mapChanged(playlist.songs, restore)
            if (songs === playlist.songs) playlist else playlist.copy(songs = songs)
        }
        val recent = mapChanged(data.recentPlays) { play ->
            val song = restore(play.song)
            if (song === play.song) play else play.copy(song = song)
        }
        if (playlists === data.playlists && favorites === data.favoritePlaylists && recent === data.recentPlays &&
            overrides == data.lyricOverrides
        ) return data
        return data.copy(playlists = playlists, favoritePlaylists = favorites, recentPlays = recent, lyricOverrides = overrides)
    }

    fun collectOverrides(data: SyncData): List<SyncSong> {
        val latestByIdentity = HashMap<String, SyncSong>()
        val songs = data.lyricOverrides.asSequence() + data.playlists.asSequence().flatMap { it.songs.asSequence() } +
            data.favoritePlaylists.asSequence().flatMap { it.songs.asSequence() } +
            data.recentPlays.asSequence().map { it.song }
        songs.forEach { song ->
            val normalized = normalize(song)
            if (normalized.lyricSyncRevision > 0L) {
                val key = song.identity().stableKey()
                val previous = latestByIdentity[key]
                latestByIdentity[key] = if (previous == null) normalized else merge(previous, listOf(previous, normalized))
            }
        }
        return latestByIdentity.values.map(::overrideRecord).sortedBy { it.identity().stableKey() }
    }

    fun mergeOverrides(songs: List<SyncSong>): List<SyncSong> {
        val latest = HashMap<String, SyncSong>()
        songs.forEach { song ->
            val normalized = normalize(song)
            if (normalized.lyricSyncRevision > 0L) {
                val key = normalized.identity().stableKey()
                val previous = latest[key]
                latest[key] = if (previous == null) normalized else merge(previous, listOf(previous, normalized))
            }
        }
        return latest.values.map(::overrideRecord).sortedBy { it.identity().stableKey() }
    }

    private fun overrideRecord(song: SyncSong): SyncSong = SyncSong(
        id = song.id, album = song.album, mediaUri = song.mediaUri,
        channelId = song.channelId, audioId = song.audioId, subAudioId = song.subAudioId,
        matchedLyric = song.matchedLyric, matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric,
        matchedLyricSource = song.matchedLyricSource, matchedSongId = song.matchedSongId,
        lyricSyncRevision = song.lyricSyncRevision, lyricSyncEdited = song.lyricSyncEdited
    )

    fun normalize(song: SyncSong): SyncSong {
        if (song.lyricSyncEdited == null && song.lyricSyncRevision == 0L &&
            !hasMatchedLyrics(song) && !hasOriginalLyrics(song)
        ) return song
        val edited = song.lyricSyncEdited == true
        val revision = if (song.lyricSyncEdited == null) 0L else maxOf(song.lyricSyncRevision, if (edited) 1L else 0L)
        return if (edited) normalizeUserEdit(song, revision) else normalizeCache(song, revision)
    }

    private fun normalizeUserEdit(song: SyncSong, revision: Long): SyncSong {
        if (song.lyricSyncEdited == true && song.lyricSyncRevision == revision && !hasOriginalLyrics(song)) return song
        return song.copy(
            lyricSyncEdited = true, lyricSyncRevision = revision,
            originalLyric = null, originalTranslatedLyric = null, originalRomanizedLyric = null
        )
    }

    private fun normalizeCache(song: SyncSong, revision: Long): SyncSong {
        if (song.lyricSyncEdited == false && song.lyricSyncRevision == revision &&
            !hasMatchedLyrics(song) && !hasOriginalLyrics(song)
        ) return song
        return song.copy(
            lyricSyncEdited = false,
            lyricSyncRevision = revision,
            matchedLyric = null,
            matchedTranslatedLyric = null,
            matchedRomanizedLyric = null,
            originalLyric = null,
            originalTranslatedLyric = null,
            originalRomanizedLyric = null
        )
    }

    private fun hasMatchedLyrics(song: SyncSong): Boolean =
        song.matchedLyric != null || song.matchedTranslatedLyric != null || song.matchedRomanizedLyric != null

    private fun hasOriginalLyrics(song: SyncSong): Boolean =
        song.originalLyric != null || song.originalTranslatedLyric != null || song.originalRomanizedLyric != null

    fun merge(selected: SyncSong, candidates: List<SyncSong>): SyncSong {
        // 未确认的旧全文由本地恢复记录保留，不将匹配来源推断成编辑
        if (selected.lyricSyncEdited == null && candidates.all { it.lyricSyncEdited == null }) {
            return normalize(selected)
        }
        val normalized = candidates.map(::normalize)
        val latest = normalized.maxWithOrNull(
            compareBy<SyncSong> { it.lyricSyncRevision }
                .thenBy { it.lyricSyncEdited == false }
                .thenBy(::lyricPayloadKey)
        ) ?: return normalize(selected)
        val result = normalize(selected)
        if (latest.lyricSyncRevision <= 0L) return result
        if (sameLyricState(result, latest)) return result
        return result.copy(
            matchedLyric = latest.matchedLyric,
            matchedTranslatedLyric = latest.matchedTranslatedLyric,
            matchedRomanizedLyric = latest.matchedRomanizedLyric,
            matchedLyricSource = latest.matchedLyricSource,
            matchedSongId = latest.matchedSongId,
            lyricSyncRevision = latest.lyricSyncRevision,
            lyricSyncEdited = latest.lyricSyncEdited
        )
    }

    private fun lyricPayloadKey(song: SyncSong): String = listOf(
        song.matchedLyric, song.matchedTranslatedLyric, song.matchedRomanizedLyric,
        song.matchedLyricSource, song.matchedSongId
    ).joinToString("") { value -> "${value?.length ?: -1}:${value.orEmpty()}" }

    private fun sameLyricState(left: SyncSong, right: SyncSong): Boolean =
        left.lyricSyncRevision == right.lyricSyncRevision && left.lyricSyncEdited == right.lyricSyncEdited &&
            left.matchedLyric == right.matchedLyric && left.matchedTranslatedLyric == right.matchedTranslatedLyric &&
            left.matchedRomanizedLyric == right.matchedRomanizedLyric && left.matchedLyricSource == right.matchedLyricSource &&
            left.matchedSongId == right.matchedSongId

    private fun <T> mapChanged(items: List<T>, transform: (T) -> T): List<T> {
        var changed: MutableList<T>? = null
        items.forEachIndexed { index, item ->
            val updated = transform(item)
            if (updated !== item) {
                val result = changed ?: items.toMutableList().also { changed = it }
                result[index] = updated
            }
        }
        return changed ?: items
    }
}
