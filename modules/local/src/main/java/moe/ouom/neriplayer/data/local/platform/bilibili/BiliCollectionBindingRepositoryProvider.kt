package moe.ouom.neriplayer.data.local.platform.bilibili

import android.content.Context
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.sync.github.GitHubSyncWorker
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.webdav.WebDavSyncWorker
import moe.ouom.neriplayer.platform.bilibili.binding.BiliCollectionBindingRepository

/**
 * 专辑分 P 绑定的单例入口。
 *
 * 与跳片规则一致：本地改动后标记同步版本并排队一次同步，绑定才能随 GitHub / WebDAV 一起走。
 */
object BiliCollectionBindingRepositoryProvider {
    @Volatile
    private var instance: BiliCollectionBindingRepository? = null

    fun getInstance(context: Context): BiliCollectionBindingRepository {
        return instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(context: Context): BiliCollectionBindingRepository {
        val storage = SecureTokenStorage(context)
        return BiliCollectionBindingRepository(
            dao = NeriUserDataDatabase.getInstance(context).biliCollectionBindingDao(),
            readSyncMutationVersion = storage::getSyncMutationVersion,
            markMutation = { storage.markSyncMutation() },
            onChanged = {
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
            }
        )
    }
}
