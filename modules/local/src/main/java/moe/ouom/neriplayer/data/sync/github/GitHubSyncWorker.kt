package moe.ouom.neriplayer.data.sync.github

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this software.
 * If not, see <https://www.gnu.org/licenses/>.
 *
 * File: moe.ouom.neriplayer.data.sync.github/GitHubSyncWorker
 * Created: 2025/1/7
 */

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
import moe.ouom.neriplayer.data.sync.work.createGitHubWorkerHost
import moe.ouom.neriplayer.data.sync.work.syncWorkManager

class GitHubSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        SyncWorkerExecution(createGitHubWorkerHost(applicationContext)).execute(
            forceSync = inputData.getBoolean("force_sync", false),
            triggerByUserAction = inputData.getBoolean("trigger_by_user_action", false)
        ).toWorkResult()
    }

    companion object {
        private const val WORK_NAME = "github_sync_work"
        private const val PERIODIC_WORK_NAME = "github_sync_periodic"
        private const val DEFAULT_DELAY_MS = 5_000L

        private fun scheduler(context: Context): SyncWorkScheduler {
            val storage by lazy { SecureTokenStorage(context) }
            return SyncWorkScheduler(
                workerClass = GitHubSyncWorker::class.java,
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
            SyncWorkRequests.delayed(GitHubSyncWorker::class.java, WORK_NAME, triggerByUserAction, initialDelayMs)

        fun schedulePeriodicSync(context: Context) { scheduler(context).schedulePeriodic() }
        fun cancelAllSync(context: Context) { scheduler(context).cancel() }
        fun syncNow(context: Context) { scheduler(context).syncNow() }
    }
}
