package moe.ouom.neriplayer.data.sync.github

import moe.ouom.neriplayer.data.sync.dataset.disk.FileSyncPlaybackDatasetStore
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage

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
 * File: moe.ouom.neriplayer.data.sync.github/GitHubSyncManager
 * Updated: 2026/3/23
 */

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.common.R as CoreCommonR
import moe.ouom.neriplayer.data.model.sync.SyncResult
import moe.ouom.neriplayer.data.sync.host.createGitHubSyncBackend
import moe.ouom.neriplayer.data.sync.host.SyncServiceInstance
import moe.ouom.neriplayer.data.sync.host.AndroidSyncLocalDataStore
import moe.ouom.neriplayer.data.sync.host.AndroidSyncMergeHost
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.runtime.SyncSession
import java.io.File
import moe.ouom.neriplayer.data.sync.host.SyncProtocolUpgradeRepository
import moe.ouom.neriplayer.data.sync.host.verifyTargetSyncCompletion
import moe.ouom.neriplayer.common.locale.LanguageManager

class GitHubSyncManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val storage by lazy { SecureTokenStorage(appContext) }
    private val local by lazy { AndroidSyncLocalDataStore(appContext, storage) }
    private val protocolUpgrade = SyncProtocolUpgradeRepository(appContext)

    companion object {
        private val instance = SyncServiceInstance<GitHubSyncManager>()

        fun getInstance(context: Context): GitHubSyncManager =
            instance.get { GitHubSyncManager(context.applicationContext) }
    }

    suspend fun performSync(): Result<SyncResult> = executeSync(null)

    suspend fun performSyncForTarget(targetId: String): Result<SyncResult> =
        verifyTargetSyncCompletion(executeSync(targetId), targetId) { target ->
            protocolUpgrade.versionFlow(target).first()
        }

    private suspend fun executeSync(expectedTargetId: String?): Result<SyncResult> = protocolUpgrade.executeIfApproved {
        withContext(Dispatchers.IO) {
            val localizedContext = LanguageManager.applyLanguage(appContext)
            SyncSession(
                local = local,
                datasetStore = FileSyncPlaybackDatasetStore(File(appContext.cacheDir, "sync-v3/playback-staging")),
                merger = SyncDataMerger(AndroidSyncMergeHost(localizedContext, CoreCommonR.string.github_sync_success_detail)),
                noChangeMessage = localizedContext.getString(CoreCommonR.string.github_sync_no_change),
                initialUploadMessage = localizedContext.getString(CoreCommonR.string.sync_initial_uploaded),
                deferredMessage = localizedContext.getString(CoreCommonR.string.sync_local_changes_pending),
                inProgressError = { GitHubSyncInProgressException(localizedContext.getString(CoreCommonR.string.github_sync_in_progress)) }
            ).execute { createGitHubSyncBackend(appContext, storage, expectedTargetId) }
        }
    }
}
