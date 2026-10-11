package moe.ouom.neriplayer.data.sync.work

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import moe.ouom.neriplayer.data.sync.schedule.SyncWorkSchedulingPolicy

internal class SyncWorkScheduler(
    private val workerClass: Class<out ListenableWorker>,
    private val workName: String,
    private val periodicName: String,
    private val manager: () -> WorkManager,
    private val automaticAllowed: () -> Boolean,
    private val markMutation: () -> Unit
) {
    fun scheduleDelayed(userAction: Boolean, mark: Boolean, delayMs: Long, append: Boolean, appStartup: Boolean = false) {
        if (mark) markMutation()
        if (!userAction && !automaticAllowed()) return
        // 启动同步不能被等待播放结束的变更任务挡住，同一次启动请求仍按唯一任务去重
        val uniqueName = if (appStartup) "${workName}_startup" else workName
        manager().enqueueUniqueWork(uniqueName, delayedPolicy(userAction, append), delayedRequest(userAction, delayMs, appStartup))
    }

    fun delayedRequest(userAction: Boolean, delayMs: Long, appStartup: Boolean = false): OneTimeWorkRequest =
        SyncWorkRequests.delayed(workerClass, workName, userAction, delayMs, appStartup)

    fun schedulePeriodic() {
        val request = SyncWorkRequests.periodic(workerClass, periodicName)
        manager().enqueueUniquePeriodicWork(periodicName, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel() {
        manager().cancelAllWorkByTag(workName)
        manager().cancelAllWorkByTag(periodicName)
    }

    fun syncNow() {
        val request = SyncWorkRequests.immediate(workerClass, workName)
        manager().enqueue(request)
    }

    companion object {
        fun delayedPolicy(userAction: Boolean, append: Boolean): ExistingWorkPolicy =
            if (SyncWorkSchedulingPolicy.append(userAction, append)) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
    }
}
