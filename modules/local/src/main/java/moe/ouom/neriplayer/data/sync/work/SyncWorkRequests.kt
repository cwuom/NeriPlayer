package moe.ouom.neriplayer.data.sync.work

import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

internal object SyncWorkRequests {
    fun delayed(worker: Class<out ListenableWorker>, tag: String, userAction: Boolean, delayMs: Long): OneTimeWorkRequest =
        OneTimeWorkRequest.Builder(worker).setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(tag).setInputData(workDataOf("trigger_by_user_action" to userAction)).build()

    fun periodic(worker: Class<out ListenableWorker>, tag: String): PeriodicWorkRequest =
        PeriodicWorkRequest.Builder(worker, 1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).addTag(tag).build()

    fun immediate(worker: Class<out ListenableWorker>, providerTag: String): OneTimeWorkRequest = OneTimeWorkRequest.Builder(worker)
        .addTag("sync_now").addTag(providerTag).setInputData(workDataOf("force_sync" to true)).build()
}
