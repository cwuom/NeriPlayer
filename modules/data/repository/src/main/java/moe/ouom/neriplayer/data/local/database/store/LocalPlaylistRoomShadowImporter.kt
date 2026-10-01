package moe.ouom.neriplayer.data.local.database.store

import android.content.Context
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository

internal class LocalPlaylistRoomShadowImporter(
    context: Context,
    private val repository: LocalPlaylistRepository
) {
    private val appContext = context.applicationContext

    suspend fun importIfNeeded() {
        if (!repository.awaitInitialized()) {
            NPLogger.w(TAG, "Skip Room shadow import because playlist load failed")
            return
        }

        val playlists = repository.playlists.value
        val sourceDigest = LocalPlaylistRoomStore.sourceDigest(playlists)
        val store = LocalPlaylistRoomStore(
            database = NeriUserDataDatabase.getInstance(appContext)
        )
        if (store.isRoomPrimary()) {
            NPLogger.d(TAG, "Skip legacy shadow import because Room is already primary")
            return
        }
        val result = runCatching {
            store.importShadowSnapshotIfChanged(
                playlists = playlists,
                sourceDigest = sourceDigest
            )
        }.getOrElse { error ->
            NPLogger.e(TAG, "Room shadow import failed; JSON remains authoritative", error)
            return
        }

        when (result.status) {
            LocalPlaylistRoomShadowImportStatus.IMPORTED -> {
                NPLogger.i(
                    TAG,
                    "Room shadow import completed: playlists=${result.playlistCount}, " +
                        "members=${result.memberCount}"
                )
            }

            LocalPlaylistRoomShadowImportStatus.SKIPPED_UNCHANGED -> {
                NPLogger.d(TAG, "Room shadow import skipped because source digest is unchanged")
            }

            LocalPlaylistRoomShadowImportStatus.SKIPPED_NOT_EQUIVALENT -> {
                NPLogger.w(
                    TAG,
                    "Room shadow import skipped because mapper is not equivalent: " +
                        result.firstMismatch
                )
            }
        }
    }

    private companion object {
        const val TAG = "LocalPlaylistRoomShadow"
    }
}
