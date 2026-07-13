package moe.ouom.neriplayer.data.backup

import moe.ouom.neriplayer.data.local.playlist.model.DISPLAY_ORDER_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.local.playlist.model.LocalPlaylist
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.sync.github.SyncPlaylist
import moe.ouom.neriplayer.data.sync.github.SyncSong
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken

internal data class BackupPlaylistRestoreResult(
    val playlist: LocalPlaylist,
    val addedSongs: Int,
    val maxAssignedAddedAt: Long
) {
    val hasChanges: Boolean
        get() = addedSongs > 0
}

internal object BackupPlaylistRestorePolicy {
    fun createPlaylist(
        playlistId: Long,
        playlistName: String,
        imported: SyncPlaylist,
        addedAtFloor: Long,
        modifiedAt: Long,
        allocateTokens: (Int) -> List<SyncCausalToken>
    ): BackupPlaylistRestoreResult {
        val importedSongs = imported.normalizedForDisplayOrder()
            .songs
            .map { it.toRestorableSong() }
        val restored = restoreSongs(importedSongs, addedAtFloor, allocateTokens)
        return BackupPlaylistRestoreResult(
            playlist = LocalPlaylist(
                id = playlistId,
                name = playlistName,
                songs = restored.songs.toMutableList(),
                modifiedAt = modifiedAt,
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            ),
            addedSongs = restored.songs.size,
            maxAssignedAddedAt = restored.maxAssignedAddedAt
        )
    }

    fun mergePlaylist(
        existing: LocalPlaylist,
        imported: SyncPlaylist,
        addedAtFloor: Long,
        modifiedAt: Long,
        allocateTokens: (Int) -> List<SyncCausalToken>
    ): BackupPlaylistRestoreResult {
        val knownIdentities = existing.songs
            .mapTo(HashSet(existing.songs.size)) { it.identity() }
        val missingSongs = imported.normalizedForDisplayOrder()
            .songs
            .asSequence()
            .map { it.toRestorableSong() }
            .filter { knownIdentities.add(it.identity()) }
            .toList()

        if (missingSongs.isEmpty()) {
            return BackupPlaylistRestoreResult(
                playlist = existing,
                addedSongs = 0,
                maxAssignedAddedAt = addedAtFloor
            )
        }

        val restored = restoreSongs(missingSongs, addedAtFloor, allocateTokens)
        return BackupPlaylistRestoreResult(
            playlist = existing.copy(
                songs = (restored.songs + existing.songs).toMutableList(),
                modifiedAt = modifiedAt,
                songOrderVersion = DISPLAY_ORDER_SONG_ORDER_VERSION
            ),
            addedSongs = restored.songs.size,
            maxAssignedAddedAt = restored.maxAssignedAddedAt
        )
    }

    private fun restoreSongs(
        songs: List<SongItem>,
        addedAtFloor: Long,
        allocateTokens: (Int) -> List<SyncCausalToken>
    ): RestoredSongs {
        if (songs.isEmpty()) {
            return RestoredSongs(emptyList(), addedAtFloor)
        }

        val tokens = allocateTokens(songs.size)
        require(tokens.size == songs.size) {
            "恢复 token 数量不匹配: songs=${songs.size} tokens=${tokens.size}"
        }
        require(tokens.toSet().size == tokens.size) { "恢复 token 不能重复" }

        val newestAddedAt = Math.addExact(addedAtFloor, songs.size.toLong())
        val restoredSongs = songs.mapIndexed { index, song ->
            song.copy(
                addedAt = newestAddedAt - index,
                syncMembershipTokens = listOf(tokens[index])
            )
        }
        check(restoredSongs.all { it.addedAt > addedAtFloor }) {
            "恢复歌曲 addedAt 必须全部高于 floor=$addedAtFloor"
        }

        return RestoredSongs(restoredSongs, newestAddedAt)
    }

    private fun SyncSong.toRestorableSong(): SongItem {
        return copy(syncMembershipTokens = syncMembershipTokens.orEmpty()).toSongItem()
    }

    private data class RestoredSongs(
        val songs: List<SongItem>,
        val maxAssignedAddedAt: Long
    )
}
