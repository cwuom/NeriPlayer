package moe.ouom.neriplayer.data.sync.merge.song

import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.hasSyncLyricText
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.sync.identity.identity

object SyncSongLyricMergePolicy {
    fun prepareLegacy(song: SyncSong, optimize: Boolean = false): SyncSong {
        if (optimize && song.lyricSyncEdited == null && song.hasSyncLyricText() && !isBilibili(song)) return normalizeCache(song, 0L)
        return normalize(song)
    }

    fun prepareLegacy(data: SyncData, optimize: Boolean = false): SyncData {
        if (!optimize) return converge(data)
        val playlists = mapChanged(data.playlists) { playlist ->
            val songs = mapChanged(playlist.songs) { prepareLegacy(it, optimize) }
            if (songs === playlist.songs) playlist else playlist.copy(songs = songs)
        }
        val favorites = mapChanged(data.favoritePlaylists) { playlist ->
            val songs = mapChanged(playlist.songs) { prepareLegacy(it, optimize) }
            if (songs === playlist.songs) playlist else playlist.copy(songs = songs)
        }
        val recent = mapChanged(data.recentPlays) { play ->
            val song = prepareLegacy(play.song, optimize)
            if (song === play.song) play else play.copy(song = song)
        }
        val overrides = mapChanged(data.lyricOverrides) { prepareLegacy(it, optimize) }
        val prepared = if (playlists === data.playlists && favorites === data.favoritePlaylists &&
            recent === data.recentPlays && overrides === data.lyricOverrides
        ) data else data.copy(playlists = playlists, favoritePlaylists = favorites, recentPlays = recent, lyricOverrides = overrides)
        return converge(prepared)
    }

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
                latestByIdentity[key] = mergeVersion(previous, normalized)
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
                latest[key] = mergeVersion(previous, normalized)
            }
        }
        return latest.values.map(::overrideRecord).sortedBy { it.identity().stableKey() }
    }

    private fun overrideRecord(song: SyncSong): SyncSong = SyncSong(
        id = song.id, album = song.album, mediaUri = song.mediaUri,
        channelId = song.channelId, audioId = song.audioId, subAudioId = song.subAudioId,
        matchedLyric = song.matchedLyric, matchedTranslatedLyric = song.matchedTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric,
        originalLyric = song.originalLyric, originalTranslatedLyric = song.originalTranslatedLyric,
        originalRomanizedLyric = song.originalRomanizedLyric,
        matchedLyricSource = song.matchedLyricSource, matchedSongId = song.matchedSongId,
        lyricSyncRevision = song.lyricSyncRevision, lyricSyncEdited = song.lyricSyncEdited
    )

    fun normalize(song: SyncSong): SyncSong = when (song.lyricSyncEdited) {
        null -> normalizeUnknownLyrics(song)
        true -> normalizeUserEdit(song, maxOf(song.lyricSyncRevision, 1L))
        false -> normalizeKnownCache(song)
    }

    private fun normalizeUnknownLyrics(song: SyncSong): SyncSong {
        if (song.hasSyncLyricText()) return normalizeLegacyLyrics(song)
        if (song.lyricSyncRevision == 0L) return song
        return normalizeCache(song, 0L)
    }

    private fun normalizeKnownCache(song: SyncSong): SyncSong {
        if (song.lyricSyncRevision <= 0L && song.hasSyncLyricText() && isBilibili(song)) return normalizeLegacyLyrics(song)
        return normalizeCache(song, maxOf(song.lyricSyncRevision, 0L))
    }

    private fun normalizeUserEdit(song: SyncSong, revision: Long): SyncSong {
        if (song.lyricSyncEdited == true && song.lyricSyncRevision == revision) return song
        return song.copy(lyricSyncEdited = true, lyricSyncRevision = revision)
    }

    private fun normalizeLegacyLyrics(song: SyncSong): SyncSong = song.copy(
        lyricSyncEdited = true, lyricSyncRevision = 1L,
        matchedLyric = song.matchedLyric ?: song.originalLyric,
        matchedTranslatedLyric = song.matchedTranslatedLyric ?: song.originalTranslatedLyric,
        matchedRomanizedLyric = song.matchedRomanizedLyric ?: song.originalRomanizedLyric
    )

    private fun isBilibili(song: SyncSong): Boolean {
        val channel = song.channelId?.trim().orEmpty()
        if (channel.isNotEmpty()) return channel.equals("bilibili", ignoreCase = true)
        return song.identity().album.startsWith("bilibili", ignoreCase = true)
    }

    private fun normalizeCache(song: SyncSong, revision: Long): SyncSong {
        if (song.lyricSyncEdited == false && song.lyricSyncRevision == revision &&
            !song.hasSyncLyricText()
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

    private fun mergeVersion(previous: SyncSong?, incoming: SyncSong): SyncSong {
        if (previous == null) return incoming
        if (sameLyricState(previous, incoming)) return previous
        return merge(previous, listOf(previous, incoming))
    }

    fun merge(selected: SyncSong, candidates: List<SyncSong>): SyncSong {
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
            originalLyric = latest.originalLyric,
            originalTranslatedLyric = latest.originalTranslatedLyric,
            originalRomanizedLyric = latest.originalRomanizedLyric,
            matchedLyricSource = latest.matchedLyricSource,
            matchedSongId = latest.matchedSongId,
            lyricSyncRevision = latest.lyricSyncRevision,
            lyricSyncEdited = latest.lyricSyncEdited
        )
    }

    private fun lyricPayloadKey(song: SyncSong): String = listOf(
        song.matchedLyric, song.matchedTranslatedLyric, song.matchedRomanizedLyric,
        song.originalLyric, song.originalTranslatedLyric, song.originalRomanizedLyric,
        song.matchedLyricSource, song.matchedSongId
    ).joinToString("") { value -> "${value?.length ?: -1}:${value.orEmpty()}" }

    private fun sameLyricState(left: SyncSong, right: SyncSong): Boolean =
        left.lyricSyncRevision == right.lyricSyncRevision && left.lyricSyncEdited == right.lyricSyncEdited &&
            sameMatchedLyrics(left, right) && sameOriginalLyrics(left, right) &&
            left.matchedLyricSource == right.matchedLyricSource && left.matchedSongId == right.matchedSongId

    private fun sameMatchedLyrics(left: SyncSong, right: SyncSong): Boolean =
        left.matchedLyric == right.matchedLyric && left.matchedTranslatedLyric == right.matchedTranslatedLyric &&
            left.matchedRomanizedLyric == right.matchedRomanizedLyric

    private fun sameOriginalLyrics(left: SyncSong, right: SyncSong): Boolean =
        left.originalLyric == right.originalLyric && left.originalTranslatedLyric == right.originalTranslatedLyric &&
            left.originalRomanizedLyric == right.originalRomanizedLyric

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
