package moe.ouom.neriplayer.data.sync.work

import androidx.work.ListenableWorker
import moe.ouom.neriplayer.data.model.sync.SyncWorkerOutcome

internal fun SyncWorkerOutcome.toWorkResult(): ListenableWorker.Result = when (this) {
    SyncWorkerOutcome.SUCCESS -> ListenableWorker.Result.success()
    SyncWorkerOutcome.RETRY -> ListenableWorker.Result.retry()
    SyncWorkerOutcome.FAILURE -> ListenableWorker.Result.failure()
}
