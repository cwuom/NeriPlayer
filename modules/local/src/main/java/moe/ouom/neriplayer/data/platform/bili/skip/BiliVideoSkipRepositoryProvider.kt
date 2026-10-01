package moe.ouom.neriplayer.data.platform.bili.skip

import android.content.Context
import moe.ouom.neriplayer.data.local.database.maintenance.LegacyJsonCleanupRequests
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.store.BiliVideoSkipRoomStore
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker

object BiliVideoSkipRepositoryProvider {
    @Volatile
    private var instance: BiliVideoSkipRepository? = null

    fun getInstance(context: Context): BiliVideoSkipRepository {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(context: Context): BiliVideoSkipRepository {
        val storage = SecureTokenStorage(context)
        return BiliVideoSkipRepository(
            store = BiliVideoSkipRoomStore(NeriUserDataDatabase.getInstance(context)),
            legacyDirectory = context.filesDir,
            readSyncMutationVersion = storage::getSyncMutationVersion,
            onRulesChanged = {
                storage.markSyncMutation()
                GitHubSyncWorker.scheduleDelayedSync(
                    context,
                    triggerByUserAction = false,
                    markMutation = false
                )
                WebDavSyncWorker.scheduleDelayedSync(
                    context,
                    triggerByUserAction = false,
                    markMutation = false
                )
            },
            scheduleLegacyCleanup = { reason ->
                LegacyJsonCleanupRequests.schedule(context, reason)
            }
        )
    }
}
