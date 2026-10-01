package moe.ouom.neriplayer.data.sync.webdav

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkerExecution
import moe.ouom.neriplayer.data.sync.work.SyncWorkScheduler
import moe.ouom.neriplayer.data.sync.work.SyncWorkRequests
import moe.ouom.neriplayer.data.sync.work.toWorkResult
import moe.ouom.neriplayer.data.sync.work.createWebDavWorkerHost
import moe.ouom.neriplayer.data.sync.store.webdav.WebDavStorage
import moe.ouom.neriplayer.data.sync.work.syncWorkManager

class WebDavSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SyncWorkerExecution(createWebDavWorkerHost(applicationContext)).execute(
            forceSync = inputData.getBoolean("force_sync", false),
            triggerByUserAction = inputData.getBoolean("trigger_by_user_action", false)
        ).toWorkResult()
    }

    companion object {
        private const val WORK_NAME = "webdav_sync_work"
        private const val PERIODIC_WORK_NAME = "webdav_sync_periodic"
        private const val DEFAULT_DELAY_MS = 5_000L

        private fun scheduler(context: Context): SyncWorkScheduler {
            val storage by lazy { WebDavStorage(context) }
            return SyncWorkScheduler(
                workerClass = WebDavSyncWorker::class.java,
                workName = WORK_NAME,
                periodicName = PERIODIC_WORK_NAME,
                manager = { syncWorkManager(context) },
                automaticAllowed = { storage.isConfigured() && storage.isAutoSyncEnabled() },
                markMutation = { SecureTokenStorage(context).markSyncMutation() }
            )
        }

        fun scheduleDelayedSync(
            context: Context,
            triggerByUserAction: Boolean = false,
            markMutation: Boolean = false,
            initialDelayMs: Long = DEFAULT_DELAY_MS,
            appendToCurrentWork: Boolean = false
        ) {
            scheduler(context).scheduleDelayed(triggerByUserAction, markMutation, initialDelayMs, appendToCurrentWork)
        }

        internal fun delayedSyncWorkPolicy(triggerByUserAction: Boolean, appendToCurrentWork: Boolean): ExistingWorkPolicy =
            SyncWorkScheduler.delayedPolicy(triggerByUserAction, appendToCurrentWork)

        internal fun buildDelayedSyncRequest(triggerByUserAction: Boolean, initialDelayMs: Long): OneTimeWorkRequest =
            SyncWorkRequests.delayed(WebDavSyncWorker::class.java, WORK_NAME, triggerByUserAction, initialDelayMs)

        fun schedulePeriodicSync(context: Context) { scheduler(context).schedulePeriodic() }
        fun cancelAllSync(context: Context) { scheduler(context).cancel() }
    }
}
