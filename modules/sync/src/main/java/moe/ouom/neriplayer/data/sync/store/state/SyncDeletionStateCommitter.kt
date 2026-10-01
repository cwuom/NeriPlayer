package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy

internal class SyncDeletionStateCommitter(private val encryptedPrefs: SharedPreferences) {
    private val gson = Gson()

    fun setDeletionStateIfMutationVersion(
        expectedMutationVersion: Long,
        recentPlayDeletions: List<SyncRecentPlayDeletion>,
        playlistSongDeletions: List<SyncPlaylistSongDeletion>
    ): Boolean {
        val normalizedRecent = normalizeRecentPlayDeletions(recentPlayDeletions)
        val normalizedPlaylist = normalizePlaylistSongDeletions(playlistSongDeletions)
        return synchronized(syncMutationLock) {
            if (encryptedPrefs.getLong(KEY_SYNC_MUTATION_VERSION, 0L) != expectedMutationVersion) {
                return@synchronized false
            }
            check(
                encryptedPrefs.commitEdit {
                    if (normalizedRecent.isEmpty()) {
                        remove(KEY_RECENT_PLAY_DELETIONS)
                    } else {
                        putString(KEY_RECENT_PLAY_DELETIONS, gson.toJson(normalizedRecent))
                    }
                    if (normalizedPlaylist.isEmpty()) {
                        remove(KEY_PLAYLIST_SONG_DELETIONS)
                    } else {
                        putString(KEY_PLAYLIST_SONG_DELETIONS, gson.toJson(normalizedPlaylist))
                    }
                }
            ) { "Failed to persist guarded sync deletion state" }
            true
        }
    }

    private fun normalizeRecentPlayDeletions(
        deletions: List<SyncRecentPlayDeletion>
    ): List<SyncRecentPlayDeletion> {
        return deletions
            .groupBy { it.stableKey() }
            .map { (_, snapshots) ->
                snapshots.maxWithOrNull(
                    compareBy<SyncRecentPlayDeletion> { it.deletedAt }
                        .thenBy { it.deviceId }
                ) ?: return@map null
            }
            .filterNotNull()
            .sortedByDescending { it.deletedAt }
            .take(MAX_RECENT_PLAY_DELETIONS)
    }

    private fun normalizePlaylistSongDeletions(
        deletions: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return SyncPlaylistDeletionPolicy.limitDeletions(deletions)
    }
}
