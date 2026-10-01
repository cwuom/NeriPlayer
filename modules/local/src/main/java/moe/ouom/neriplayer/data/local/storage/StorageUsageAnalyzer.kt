package moe.ouom.neriplayer.data.local.storage

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
 * File: moe.ouom.neriplayer.data.local.storage/StorageUsageAnalyzer
 * Created: 2026/7/9
 */

import moe.ouom.neriplayer.data.local.storage.host.AndroidStorageCacheFiles
import moe.ouom.neriplayer.data.local.storage.host.AndroidStoragePlatformCaches
import moe.ouom.neriplayer.data.local.storage.host.AndroidStorageUsageSource
import moe.ouom.neriplayer.data.local.storage.host.storageLocations
import moe.ouom.neriplayer.data.local.storage.presentation.StorageUsagePresenter
import moe.ouom.neriplayer.data.local.storage.cleanup.StorageCacheCleaner
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult
import moe.ouom.neriplayer.data.model.storage.StorageCacheClearOptions
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary
import moe.ouom.neriplayer.data.local.storage.scan.StorageUsageScanner
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun analyzeStorageUsage(context: Context): StorageUsageSummary = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    val snapshot = StorageUsageScanner(storageLocations(appContext), AndroidStorageUsageSource(appContext)).scan()
    StorageUsagePresenter(appContext.resources).present(snapshot)
}

suspend fun clearExtraStorageCaches(
    context: Context,
    options: StorageCacheClearOptions
): ExtraCacheClearResult = withContext(Dispatchers.IO) {
    val appContext = context.applicationContext
    StorageCacheCleaner(
        storageLocations(appContext),
        AndroidStorageCacheFiles(appContext),
        AndroidStoragePlatformCaches(appContext)
    ).clear(options)
}
