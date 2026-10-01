package moe.ouom.neriplayer.data.sync.store.state

import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistSongDeletion
import moe.ouom.neriplayer.data.sync.merge.playlist.SyncPlaylistDeletionPolicy

internal class SyncPlaylistSongDeletionStore(private val encryptedPrefs: SharedPreferences) {
    private val gson = Gson()
    private val mutation = SyncMutationVersionStore(encryptedPrefs)

    fun getPlaylistSongDeletions(): List<SyncPlaylistSongDeletion> {
        return synchronized(syncMutationLock) {
            val raw = encryptedPrefs.getString(KEY_PLAYLIST_SONG_DELETIONS, null).orEmpty()
            if (raw.isBlank()) {
                return@synchronized emptyList()
            }
            val parsed = runCatching {
                val type = object : TypeToken<List<SyncPlaylistSongDeletion>>() {}.type
                gson.fromJson<List<SyncPlaylistSongDeletion>>(raw, type).orEmpty()
            }.getOrElse { emptyList() }
            normalizePlaylistSongDeletions(parsed)
        }
    }

    fun setPlaylistSongDeletions(deletions: List<SyncPlaylistSongDeletion>) {
        synchronized(syncMutationLock) {
            persistPlaylistSongDeletionsLocked(
                deletions = normalizePlaylistSongDeletions(deletions),
                bumpVersion = false
            )
        }
    }

    fun addPlaylistSongDeletions(deletions: List<SyncPlaylistSongDeletion>) {
        if (deletions.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            persistPlaylistSongDeletionsLocked(
                deletions = normalizePlaylistSongDeletions(getPlaylistSongDeletions() + deletions),
                bumpVersion = true
            )
        }
    }

    fun removePlaylistSongDeletions(
        playlistId: Long,
        identities: Collection<SongIdentity>
    ) {
        if (identities.isEmpty()) {
            return
        }
        synchronized(syncMutationLock) {
            val current = getPlaylistSongDeletions()
            val remaining = SyncPlaylistDeletionPolicy.clearLegacyDeletionsForReaddedSongs(
                deletions = current,
                playlistId = playlistId,
                identities = identities
            )
            if (remaining != current) {
                persistPlaylistSongDeletionsLocked(remaining, bumpVersion = true)
            }
        }
    }

    fun removePlaylistSongDeletionsForPlaylist(playlistId: Long) {
        synchronized(syncMutationLock) {
            val current = getPlaylistSongDeletions()
            val remaining = current
                .filterNot { it.playlistId == playlistId }
            if (remaining != current) {
                persistPlaylistSongDeletionsLocked(remaining, bumpVersion = true)
            }
        }
    }

    private fun persistPlaylistSongDeletionsLocked(
        deletions: List<SyncPlaylistSongDeletion>,
        bumpVersion: Boolean
    ) {
        val normalized = normalizePlaylistSongDeletions(deletions)
        check(
            encryptedPrefs.commitEdit {
                if (normalized.isEmpty()) {
                    remove(KEY_PLAYLIST_SONG_DELETIONS)
                } else {
                    putString(KEY_PLAYLIST_SONG_DELETIONS, gson.toJson(normalized))
                }
                if (bumpVersion) {
                    mutation.bump(this)
                }
            }
        ) { "Failed to persist playlist deletion state" }
    }

    private fun normalizePlaylistSongDeletions(
        deletions: List<SyncPlaylistSongDeletion>
    ): List<SyncPlaylistSongDeletion> {
        return SyncPlaylistDeletionPolicy.limitDeletions(deletions)
    }
}
