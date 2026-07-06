package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.model.identity
import moe.ouom.neriplayer.data.model.stableKey

internal object SyncPlaylistDeletionPolicy {
    fun mergeDeletions(
        local: List<SyncPlaylistSongDeletion>,
        remote: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return (local + remote)
            .groupBy(SyncPlaylistSongDeletion::stableKey)
            .mapNotNull { (_, snapshots) ->
                snapshots.maxWithOrNull(
                    compareBy<SyncPlaylistSongDeletion> { it.deletedAt }
                        .thenBy { it.deviceId }
                )
            }
            .sortedByDescending { it.deletedAt }
    }

    fun applyDeletions(
        playlistId: Long,
        songs: List<SyncSong>,
        deletions: List<SyncPlaylistSongDeletion>
    ): List<SyncSong> {
        if (songs.isEmpty() || deletions.isEmpty()) {
            return songs
        }

        val deletionsBySong = deletions
            .asSequence()
            .filter { it.playlistId == playlistId }
            .associateBy { it.identity().stableKey() }
        if (deletionsBySong.isEmpty()) {
            return songs
        }

        return songs.filter { song ->
            val deletion = deletionsBySong[song.identity().stableKey()]
            deletion == null || effectiveAddedAt(song) > deletion.deletedAt
        }
    }

    fun pruneResolvedDeletions(
        deletions: List<SyncPlaylistSongDeletion>,
        playlists: List<SyncPlaylist>
    ): List<SyncPlaylistSongDeletion> {
        if (deletions.isEmpty()) {
            return emptyList()
        }

        val deletionByKey = deletions.associateBy(SyncPlaylistSongDeletion::stableKey)
        val resolvedKeys = buildSet {
            playlists.asSequence()
                .filterNot(SyncPlaylist::isDeleted)
                .forEach { playlist ->
                    playlist.songs.forEach { song ->
                        val key = "${playlist.id}|${song.identity().stableKey()}"
                        val deletion = deletionByKey[key] ?: return@forEach
                        if (effectiveAddedAt(song) > deletion.deletedAt) {
                            add(key)
                        }
                    }
                }
        }

        return deletions
            .filterNot { it.stableKey() in resolvedKeys }
            .sortedByDescending { it.deletedAt }
    }

    private fun effectiveAddedAt(song: SyncSong): Long {
        return song.addedAt.takeIf { it > 0L } ?: Long.MIN_VALUE
    }
}
