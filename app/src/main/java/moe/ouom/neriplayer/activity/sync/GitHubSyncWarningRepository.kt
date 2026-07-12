package moe.ouom.neriplayer.activity.sync

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
 * File: moe.ouom.neriplayer.activity.sync/GitHubSyncWarningRepository
 * Created: 2026/7/13
 */

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.sync.github.SecureTokenStorage

internal data class GitHubSyncWarningState(
    val hasRepoInfo: Boolean,
    val hasSyncHistory: Boolean,
    val isConfigured: Boolean,
    val isDismissed: Boolean
)

internal class GitHubSyncWarningRepository(
    private val context: Context
) {
    suspend fun loadState(): GitHubSyncWarningState = withContext(Dispatchers.IO) {
        val storage = SecureTokenStorage(context)
        GitHubSyncWarningState(
            hasRepoInfo = !storage.getRepoOwner().isNullOrEmpty() || !storage.getRepoName().isNullOrEmpty(),
            hasSyncHistory = storage.getLastSyncTime() > 0,
            isConfigured = storage.isConfigured(),
            isDismissed = storage.isTokenWarningDismissed()
        )
    }

    suspend fun setDismissed(dismissed: Boolean) {
        withContext(Dispatchers.IO) {
            SecureTokenStorage(context).setTokenWarningDismissed(dismissed)
        }
    }
}
