package moe.ouom.neriplayer.core.player.persistence

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.identity.remoteSourceIdentityOrNull
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.identity.identity
import moe.ouom.neriplayer.data.sync.mapping.toSongItem
import moe.ouom.neriplayer.data.sync.merge.song.SyncSongLyricMergePolicy

internal class PlaybackLyricOverrideProjection(overrides: List<SyncSong>) {
    private val legacyByIdentity = overrides.asSequence().filter { it.lyricSyncEdited == null }
        .associateBy { it.identity().stableKey() }
    private val byIdentity = buildMap {
        for (song in overrides) {
            val normalized = SyncSongLyricMergePolicy.normalize(song)
            if (normalized.lyricSyncRevision <= 0L) continue
            val key = normalized.identity().stableKey()
            val previous = get(key)
            put(key, if (previous == null) normalized else
                SyncSongLyricMergePolicy.merge(previous, listOf(previous, normalized)))
        }
    }

    fun song(existing: SongItem): SongItem {
        val key = playbackLyricIdentityKey(existing)
        val remote = byIdentity[key] ?: return recoverLegacyLyrics(existing, key)
        val local = SyncSongLyricMergePolicy.normalize(localLyricState(existing, remote))
        if (local.lyricSyncRevision > remote.lyricSyncRevision) return existing
        val latest = if (remote.lyricSyncRevision > maxOf(local.lyricSyncRevision, 1L)) remote else {
            SyncSongLyricMergePolicy.merge(remote, listOf(remote, local))
        }
        val restored = latest.toSongItem(existing)
        val projected = existing.copy(
            matchedLyric = restored.matchedLyric,
            matchedTranslatedLyric = restored.matchedTranslatedLyric,
            matchedRomanizedLyric = restored.matchedRomanizedLyric,
            originalLyric = restored.originalLyric,
            originalTranslatedLyric = restored.originalTranslatedLyric,
            originalRomanizedLyric = restored.originalRomanizedLyric,
            matchedLyricSource = restored.matchedLyricSource,
            matchedSongId = restored.matchedSongId,
            lyricSyncEdited = restored.lyricSyncEdited,
            lyricSyncRevision = restored.lyricSyncRevision
        )
        return if (projected == existing) existing else projected
    }

    private fun recoverLegacyLyrics(existing: SongItem, key: String): SongItem {
        if (existing.lyricSyncRevision > 0L || existing.lyricSyncEdited == true ||
            existing.matchedLyric != null || existing.matchedTranslatedLyric != null || existing.matchedRomanizedLyric != null
        ) return existing
        val legacy = legacyByIdentity[key] ?: return existing
        val restored = legacy.copy(lyricSyncRevision = 0L).toSongItem()
        return existing.copy(
            matchedLyric = restored.matchedLyric,
            matchedTranslatedLyric = restored.matchedTranslatedLyric,
            matchedRomanizedLyric = restored.matchedRomanizedLyric,
            matchedLyricSource = restored.matchedLyricSource,
            matchedSongId = restored.matchedSongId,
            originalLyric = restored.originalLyric,
            originalTranslatedLyric = restored.originalTranslatedLyric,
            originalRomanizedLyric = restored.originalRomanizedLyric,
            lyricSyncEdited = null,
            lyricSyncRevision = 0L
        )
    }

    private fun localLyricState(song: SongItem, identity: SyncSong): SyncSong {
        return identity.copy(
            matchedLyric = song.matchedLyric,
            matchedTranslatedLyric = song.matchedTranslatedLyric,
            matchedRomanizedLyric = song.matchedRomanizedLyric,
            originalLyric = song.originalLyric,
            originalTranslatedLyric = song.originalTranslatedLyric,
            originalRomanizedLyric = song.originalRomanizedLyric,
            matchedLyricSource = song.matchedLyricSource?.name,
            matchedSongId = song.matchedSongId,
            lyricSyncEdited = song.lyricSyncEdited,
            lyricSyncRevision = song.lyricSyncRevision
        )
    }

    fun playlist(songs: List<SongItem>): List<SongItem> {
        if (byIdentity.isEmpty() && legacyByIdentity.isEmpty()) return songs
        var changed: MutableList<SongItem>? = null
        songs.forEachIndexed { index, existing ->
            val restored = song(existing)
            if (restored !== existing) {
                val result = changed ?: songs.toMutableList().also { changed = it }
                result[index] = restored
            }
        }
        return changed ?: songs
    }

    fun restoredSnapshot(snapshot: RestoredPlayerStateSnapshot): RestoredPlayerStateSnapshot {
        val playlist = playlist(snapshot.playlist)
        val shuffleRestore = snapshot.shuffleRestorePlaylist?.let(::playlist)
        if (playlist === snapshot.playlist && shuffleRestore === snapshot.shuffleRestorePlaylist) return snapshot
        return snapshot.copy(playlist = playlist, shuffleRestorePlaylist = shuffleRestore)
    }
}

private fun playbackLyricIdentityKey(song: SongItem): String =
    song.remoteSourceIdentityOrNull()?.stableKey() ?: song.stableKey()

internal fun playbackLyricIdentityKeys(
    current: SongItem?, active: List<SongItem>, shuffle: List<SongItem>?,
    visit: (SongItem) -> Unit = {}
): Set<String> = buildSet {
    current?.let { visit(it); add(playbackLyricIdentityKey(it)) }
    for (song in active) { visit(song); add(playbackLyricIdentityKey(song)) }
    if (shuffle != null) for (song in shuffle) { visit(song); add(playbackLyricIdentityKey(song)) }
}

internal suspend fun <T> applyFilteredPlaybackLyricOverrides(
    readTargets: suspend () -> T,
    identityKeys: (T) -> Set<String>,
    readOverrides: suspend (Set<String>) -> List<SyncSong>,
    applyIfCurrent: suspend (T, PlaybackLyricOverrideProjection) -> Boolean
) {
    while (true) {
        coroutineContext.ensureActive()
        val requested = identityKeys(readTargets())
        val projection = PlaybackLyricOverrideProjection(readOverrides(requested))
        while (true) {
            coroutineContext.ensureActive()
            val current = readTargets()
            // 队列增加歌曲时重新读取，重排同一组歌曲只需重新确认目标快照
            if (!requested.containsAll(identityKeys(current))) break
            if (applyIfCurrent(current, projection)) return
        }
    }
}
