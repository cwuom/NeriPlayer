package moe.ouom.neriplayer.data.local.playlist

import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongDuplicateIndex
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository.SongMatchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.local.audioimport.LocalAudioImportManager
import moe.ouom.neriplayer.data.local.media.LocalSongSupport
import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.local.playlist.system.LocalFilesPlaylist
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.isSyncableRemoteSong
import moe.ouom.neriplayer.data.model.sameIdentityAs
import moe.ouom.neriplayer.data.sync.model.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.SongItem

internal fun LocalPlaylistRepository.renewSongsForPlaylistRestore(songs: List<SongItem>): List<SongItem> {
    if (songs.isEmpty()) return emptyList()

    val membershipTokens = syncMutationStore.nextSyncCausalTokens(songs.size)
    check(membershipTokens.size == songs.size) {
        "Expected ${songs.size} sync membership tokens, got ${membershipTokens.size}"
    }
    return songs.mapIndexed { index, song ->
        song.copy(syncMembershipTokens = listOf(membershipTokens[index]))
    }
}

internal fun LocalPlaylistRepository.nextPlaylistSongAddedAt(playlist: LocalPlaylist, now: Long): Long {
    val latestExistingMembershipAt = playlist.songs.maxOfOrNull { song ->
        maxOf(song.membershipAddedAtMs ?: 0L, song.addedAt)
    } ?: 0L
    // membership 时间必须单调递增，即使两次导入落在同一毫秒也不能让新歌沉底
    return maxOf(now, latestExistingMembershipAt + 1L)
}

internal fun LocalPlaylistRepository.stampSongsForDisplayOrder(
    songs: List<SongItem>,
    newestAt: Long
): MutableList<SongItem> {
    return songs.mapIndexedTo(mutableListOf()) { index, song ->
        song.copy(addedAt = (newestAt - index).coerceAtLeast(1L))
    }
}

internal fun LocalPlaylistRepository.mergeNewSongsFirst(
    existingSongs: List<SongItem>,
    newSongs: List<SongItem>
): MutableList<SongItem> {
    return (newSongs + existingSongs).toMutableList()
}

internal fun LocalPlaylistRepository.buildPlaylistSongDeletionMutation(
    playlistId: Long,
    songs: List<SongItem>,
    deletedAt: Long
): LocalPlaylistSyncMutation {
    val deletions = buildPlaylistSongDeletions(playlistId, songs, deletedAt)
    return LocalPlaylistSyncMutation(addedSongDeletions = deletions)
}

internal fun LocalPlaylistRepository.buildPlaylistSongDeletionRemoval(
    playlistId: Long,
    songs: List<SongItem>
): LocalPlaylistSyncMutation {
    val remoteIdentities = songs
        .asSequence()
        .filter { it.isSyncableRemoteSong(context) }
        .map { it.identity() }
        .toList()
    if (remoteIdentities.isEmpty()) return LocalPlaylistSyncMutation()
    return LocalPlaylistSyncMutation(
        removedSongDeletions = listOf(
            PlaylistSongDeletionRemoval(
                playlistId = playlistId,
                identities = remoteIdentities
            )
        )
    )
}

internal fun LocalPlaylistRepository.buildPlaylistSongDeletions(
    playlistId: Long,
    songs: List<SongItem>,
    deletedAt: Long
): List<SyncPlaylistSongDeletion> {
    if (songs.isEmpty() || isLocalFilesPlaylist(playlistId)) {
        return emptyList()
    }

    val deviceId = syncMutationStore.getOrCreateDeviceId()
    return songs
        .asSequence()
        .filter { it.isSyncableRemoteSong(context) }
        .map { song ->
            val identity = song.identity()
            SyncPlaylistSongDeletion(
                playlistId = playlistId,
                songId = identity.id,
                album = identity.album,
                mediaUri = LocalSongSupport.sanitizeMediaUriForSync(identity.mediaUri),
                deletedAt = deletedAt,
                deviceId = deviceId,
                removedMembershipTokens = song.syncMembershipTokens.orEmpty()
            )
        }
        .toList()
}

internal suspend fun LocalPlaylistRepository.hydrateLocalSongsForPersistence(
    songs: List<SongItem>,
    hydrateLocalMetadata: Boolean = true
): List<SongItem> {
    if (!hydrateLocalMetadata) {
        return songs
    }
    if (songs.none { LocalSongSupport.isLocalSong(it, context) }) {
        return songs
    }

    return coroutineScope {
        val hydrateDispatcher = Dispatchers.IO.limitedParallelism(4)
        val hydrated = ArrayList<SongItem>(songs.size)
        // 大歌单按批次调度，避免数万首歌曲同时保留 Deferred 和结果对象
        songs.chunked(LocalPlaylistRepository.LOCAL_METADATA_HYDRATE_BATCH_SIZE).forEach { batch ->
            hydrated += batch.map { song ->
                async(hydrateDispatcher) {
                    LocalAudioImportManager.hydrateLocalSongMetadata(context, song)
                }
            }.awaitAll()
        }
        hydrated
    }
}

internal fun LocalPlaylistRepository.hasExistingSong(
    existingSongs: List<SongItem>,
    candidate: SongItem,
    includeLocalMetadataFallback: Boolean = false
): Boolean {
    return existingSongs.any { existing ->
        existing.sameIdentityAs(candidate) ||
            LocalSongSupport.hasSameLocalSource(
                first = existing,
                second = candidate,
                includeMetadataFallback = includeLocalMetadataFallback
            )
    }
}

internal fun LocalPlaylistRepository.distinctPlaylistSongs(
    songs: List<SongItem>,
    includeLocalMetadataFallback: Boolean = false
): MutableList<SongItem> {
    val duplicateIndex = SongDuplicateIndex(includeLocalMetadataFallback)
    val distinct = mutableListOf<SongItem>()
    songs.forEach { song ->
        if (duplicateIndex.contains(song)) return@forEach
        duplicateIndex.add(song)
        distinct += song
    }
    return distinct
}

internal fun LocalPlaylistRepository.filterNewSongs(
    existingSongs: List<SongItem>,
    candidates: List<SongItem>,
    includeLocalMetadataFallback: Boolean = false
): List<SongItem> {
    val accepted = SongDuplicateIndex(includeLocalMetadataFallback).apply {
        existingSongs.forEach(::add)
    }
    return candidates.filter { candidate ->
        if (accepted.contains(candidate)) {
            false
        } else {
            accepted.add(candidate)
            true
        }
    }
}

internal fun LocalPlaylistRepository.nextPlaylistId(existing: List<LocalPlaylist>): Long {
    val usedIds = existing.mapTo(HashSet(existing.size)) { it.id }
    var candidate = System.currentTimeMillis()
    while (candidate in usedIds) {
        candidate++
    }
    return candidate
}

internal fun LocalPlaylistRepository.isLocalFilesPlaylist(playlistId: Long, playlistName: String? = null): Boolean {
    return playlistId == LocalFilesPlaylist.SYSTEM_ID ||
        (playlistId < 0 && playlistName != null && LocalFilesPlaylist.matches(playlistName, context))
}

internal suspend fun LocalPlaylistRepository.createPlaylistWithSongs(
    name: String,
    songs: List<SongItem>,
    hydrateLocalMetadata: Boolean,
    preserveScannedSourceAddedAt: Boolean = false
): LocalPlaylist {
    return withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val distinctSongs = distinctPlaylistSongs(
            stampSongsForPlaylistInsert(
                songs = hydrateLocalSongsForPersistence(songs, hydrateLocalMetadata),
                addedAt = now,
                preserveScannedSourceAddedAt = preserveScannedSourceAddedAt
            )
        )
        commitPlaylistMutation {
            val list = _playlists.value.toMutableList()
            val playlist = LocalPlaylist(
                id = nextPlaylistId(list),
                name = sanitizePlaylistName(name),
                songs = distinctSongs,
                modifiedAt = now,
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            )
            list.add(playlist)
            publishLocked(list)
            playlist
        }
    }
}

internal suspend fun LocalPlaylistRepository.addSongsToPlaylistWithResult(
    playlistId: Long,
    songs: List<SongItem>,
    hydrateLocalMetadata: Boolean,
    includeLocalMetadataFallback: Boolean = false,
    preserveScannedSourceAddedAt: Boolean = false
): LocalPlaylistSongAddResult {
    return withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext LocalPlaylistSongAddResult(emptyList())
        val now = System.currentTimeMillis()
        val hydratedSongs = hydrateLocalSongsForPersistence(songs, hydrateLocalMetadata)
        commitPlaylistMutation {
            LocalPlaylistSongAddResult(
                addedSongs = addStampedSongsToPlaylistLocked(
                    playlistId = playlistId,
                    songs = hydratedSongs,
                    now = now,
                    includeLocalMetadataFallback = includeLocalMetadataFallback,
                    preserveScannedSourceAddedAt = preserveScannedSourceAddedAt
                )
            )
        }
    }
}

internal suspend fun LocalPlaylistRepository.addStampedSongsToPlaylistLocked(
    playlistId: Long,
    songs: List<SongItem>,
    now: Long,
    includeLocalMetadataFallback: Boolean = false,
    preserveScannedSourceAddedAt: Boolean = false
): List<SongItem> {
    if (songs.isEmpty()) {
        return emptyList()
    }

    var addedSongs = emptyList<SongItem>()
    var syncMutation = LocalPlaylistSyncMutation()
    val updated = _playlists.value.map { playlist ->
        if (playlist.id != playlistId) return@map playlist
        if (isLocalFilesPlaylist(playlist.id, playlist.name)) {
            return@map playlist
        }

        val existingSongs = if (preserveScannedSourceAddedAt) {
            mergeScannedMetadataIntoExistingSongs(playlist.songs, songs)
        } else {
            playlist.songs
        }
        val newSongs = filterNewSongs(
            existingSongs = existingSongs,
            candidates = songs,
            includeLocalMetadataFallback = includeLocalMetadataFallback
        )
        if (newSongs.isEmpty()) {
            if (existingSongs == playlist.songs) playlist else playlist.copy(songs = existingSongs)
        } else {
            val toAdd = stampSongsForPlaylistInsert(
                songs = newSongs,
                addedAt = nextPlaylistSongAddedAt(playlist, now),
                preserveScannedSourceAddedAt = preserveScannedSourceAddedAt
            )
            addedSongs = toAdd
            syncMutation += buildPlaylistSongDeletionRemoval(playlist.id, toAdd)
            playlist.copy(
                songs = mergeNewSongsFirst(existingSongs, toAdd),
                modifiedAt = now,
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            )
        }
    }
    publishLocked(updated, syncMutation = syncMutation)
    return addedSongs
}

internal suspend fun LocalPlaylistRepository.addSongsToLocalFilesPlaylistWithResult(
    songs: List<SongItem>,
    hydrateLocalMetadata: Boolean,
    preserveScannedSourceAddedAt: Boolean = false
): LocalPlaylistSongAddResult {
    return withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext LocalPlaylistSongAddResult(emptyList())
        val now = System.currentTimeMillis()
        val hydratedSongs = hydrateLocalSongsForPersistence(songs, hydrateLocalMetadata)
        commitPlaylistMutation {
            var addedSongs = emptyList<SongItem>()
            val updated = _playlists.value.map { playlist ->
                if (!isLocalFilesPlaylist(playlist.id, playlist.name)) {
                    return@map playlist
                }

                val existingSongs = if (preserveScannedSourceAddedAt) {
                    mergeScannedMetadataIntoExistingSongs(playlist.songs, hydratedSongs)
                } else {
                    playlist.songs
                }
                val newSongs = filterNewSongs(
                    existingSongs = existingSongs,
                    candidates = hydratedSongs,
                    includeLocalMetadataFallback = true
                )
                if (newSongs.isEmpty()) {
                    if (existingSongs == playlist.songs) playlist else playlist.copy(
                        songs = existingSongs,
                        modifiedAt = now,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                } else {
                    val toAdd = stampSongsForPlaylistInsert(
                        songs = newSongs,
                        addedAt = nextPlaylistSongAddedAt(playlist, now),
                        preserveScannedSourceAddedAt = preserveScannedSourceAddedAt
                    )
                    addedSongs = toAdd
                    // 扫描结果已经按用户看到的顺序排列，新歌置于歌单最前
                    // 既保留批次顺序，也不重排用户已经手动调整的旧歌
                    val normalizedSongs = mergeNewSongsFirst(existingSongs, toAdd)
                    playlist.copy(
                        songs = normalizedSongs.toMutableList(),
                        modifiedAt = now,
                        songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
                    )
                }
            }
            publishLocked(updated)
            LocalPlaylistSongAddResult(addedSongs)
        }
    }
}

internal fun LocalPlaylistRepository.mergeScannedMetadataIntoExistingSongs(
    existingSongs: List<SongItem>,
    scannedSongs: List<SongItem>
): MutableList<SongItem> {
    if (existingSongs.isEmpty() || scannedSongs.isEmpty()) {
        return existingSongs.toMutableList()
    }
    val scannedSongIndex = SongMatchIndex(
        songs = scannedSongs,
        includeLocalMetadataFallback = true
    )
    return existingSongs.mapTo(mutableListOf()) existingSong@{ existingSong ->
        val scannedSong = scannedSongIndex.firstMatch(existingSong)
            ?: return@existingSong existingSong
        mergeSongMetadataForPersistence(existingSong, scannedSong)
    }
}
