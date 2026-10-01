package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy

internal class SyncPlaylistMutationStore(private val encryptedPrefs: SharedPreferences,
    private val playlists: SyncPlaylistDeletionStore,
    private val songs: SyncPlaylistSongDeletionStore) {
    private val gson = Gson()
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun applyPlaylistSyncMutation(
        addedSongDeletions: List<SyncPlaylistSongDeletion>,
        removedSongDeletions: List<Pair<Long, Collection<SongIdentity>>>,
        deletedPlaylistIds: List<Long>,
        clearedPlaylistDeletionIds: List<Long>,
        restoredPlaylistIds: Set<Long>
    ): Long {
        synchronized(syncMutationLock) {
            var playlistDeletions = songs.getPlaylistSongDeletions()
            if (addedSongDeletions.isNotEmpty()) {
                playlistDeletions = SyncPlaylistDeletionPolicy.mergeDeletions(
                    playlistDeletions,
                    addedSongDeletions
                )
            }
            removedSongDeletions.forEach { (playlistId, identities) ->
                playlistDeletions = SyncPlaylistDeletionPolicy.clearLegacyDeletionsForReaddedSongs(
                    deletions = playlistDeletions,
                    playlistId = playlistId,
                    identities = identities
                )
            }
            clearedPlaylistDeletionIds.forEach { playlistId ->
                playlistDeletions = playlistDeletions.filterNot { it.playlistId == playlistId }
            }
            playlistDeletions = normalizePlaylistSongDeletions(playlistDeletions)

            val deletedIds = playlists.readDeletedPlaylistIdsLocked().toMutableSet()
            val deletedTimestamps = playlists.readDeletedPlaylistTimestampsLocked().toMutableMap()
            deletedPlaylistIds.forEach { playlistId ->
                deletedIds += playlistId
                deletedTimestamps.putIfAbsent(
                    playlistId,
                    System.currentTimeMillis().coerceAtLeast(1L)
                )
            }
            restoredPlaylistIds.forEach { playlistId ->
                deletedIds -= playlistId
                deletedTimestamps -= playlistId
            }

            val editor = encryptedPrefs.edit()
            if (playlistDeletions.isEmpty()) {
                editor.remove(KEY_PLAYLIST_SONG_DELETIONS)
            } else {
                editor.putString(KEY_PLAYLIST_SONG_DELETIONS, gson.toJson(playlistDeletions))
            }
            if (deletedIds.isEmpty()) {
                editor.remove(KEY_DELETED_PLAYLIST_IDS)
                    .remove(KEY_DELETED_PLAYLIST_TIMESTAMPS)
            } else {
                editor.putString(KEY_DELETED_PLAYLIST_IDS, deletedIds.joinToString(","))
                    .putString(KEY_DELETED_PLAYLIST_TIMESTAMPS, gson.toJson(deletedTimestamps))
            }
            val nextVersion = mutation.bump(editor)
            check(editor.commit()) { "Failed to persist playlist sync mutation" }
            return nextVersion
        }
    }

    private fun normalizePlaylistSongDeletions(
        deletions: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return SyncPlaylistDeletionPolicy.limitDeletions(deletions)
    }
}
