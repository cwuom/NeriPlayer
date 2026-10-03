package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy
import moe.ouom.neriplayer.data.sync.merge.history.SyncRecentPlayMerger

internal class SyncDeletionStateCommitter(private val encryptedPrefs: SharedPreferences, private val files: SyncDeletionStateStorage) {

    fun setDeletionStateIfMutationVersion(
        expectedMutationVersion: Long,
        recentPlayDeletions: List<SyncRecentPlayDeletion>,
        playlistSongDeletions: List<SyncPlaylistSongDeletion>
    ): Boolean {
        val normalizedRecent = SyncRecentPlayMerger.mergeRecentPlayDeletions(recentPlayDeletions, emptyList())
        val normalizedPlaylist = normalizePlaylistSongDeletions(playlistSongDeletions)
        return synchronized(syncMutationLock) {
            if (encryptedPrefs.getLong(KEY_SYNC_MUTATION_VERSION, 0L) != expectedMutationVersion) {
                return@synchronized false
            }
            check(
                files.commitEdit {
                    files.write(this, KEY_RECENT_PLAY_DELETIONS, normalizedRecent.takeIf { it.isNotEmpty() })
                    files.write(this, KEY_PLAYLIST_SONG_DELETIONS, normalizedPlaylist.takeIf { it.isNotEmpty() })
                }
            ) { "Failed to persist guarded sync deletion state" }
            true
        }
    }

    private fun normalizePlaylistSongDeletions(
        deletions: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return SyncPlaylistDeletionPolicy.mergeDeletions(deletions, emptyList())
    }
}
