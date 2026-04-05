package moe.ouom.neriplayer.data.settings

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
 * File: moe.ouom.neriplayer.data.settings/BootstrapSettingsSnapshot
 * Updated: 2026/4/5
 */

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate

private const val BOOTSTRAP_SNAPSHOT_PREFS = "bootstrap_settings_snapshot"
private const val BOOTSTRAP_SNAPSHOT_READY_KEY = "ready"
private const val BOOTSTRAP_BYPASS_PROXY_KEY = "bypass_proxy"
private const val BOOTSTRAP_DOWNLOAD_DIRECTORY_URI_KEY = "download_directory_uri"
private const val BOOTSTRAP_DOWNLOAD_DIRECTORY_LABEL_KEY = "download_directory_label"
private const val BOOTSTRAP_DOWNLOAD_FILE_NAME_TEMPLATE_KEY = "download_file_name_template"

data class BootstrapSettingsSnapshot(
    val bypassProxy: Boolean = true,
    val downloadDirectoryUri: String? = null,
    val downloadDirectoryLabel: String? = null,
    val downloadFileNameTemplate: String? = null
) {
    fun sanitized(): BootstrapSettingsSnapshot {
        return copy(
            downloadDirectoryUri = downloadDirectoryUri?.takeIf { it.isNotBlank() },
            downloadDirectoryLabel = downloadDirectoryLabel?.takeIf { it.isNotBlank() },
            downloadFileNameTemplate = normalizeDownloadFileNameTemplate(downloadFileNameTemplate)
        )
    }
}

fun readBootstrapSettingsSnapshotSync(context: Context): BootstrapSettingsSnapshot {
    readCachedBootstrapSettingsSnapshot(context)?.let { return it }

    return runBlocking {
        context.dataStore.data.first().toBootstrapSettingsSnapshot()
    }.also { snapshot ->
        persistBootstrapSettingsSnapshot(context, snapshot)
    }
}

internal suspend fun updateBootstrapSettingsSnapshot(
    context: Context,
    transform: (BootstrapSettingsSnapshot) -> BootstrapSettingsSnapshot
) {
    val currentSnapshot = readCachedBootstrapSettingsSnapshot(context)
        ?: context.dataStore.data.first().toBootstrapSettingsSnapshot()
    persistBootstrapSettingsSnapshot(context, transform(currentSnapshot))
}

internal fun persistBootstrapSettingsSnapshot(
    context: Context,
    snapshot: BootstrapSettingsSnapshot
) {
    val normalizedSnapshot = snapshot.sanitized()
    context.getSharedPreferences(BOOTSTRAP_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(BOOTSTRAP_SNAPSHOT_READY_KEY, true)
        .putBoolean(BOOTSTRAP_BYPASS_PROXY_KEY, normalizedSnapshot.bypassProxy)
        .putString(BOOTSTRAP_DOWNLOAD_DIRECTORY_URI_KEY, normalizedSnapshot.downloadDirectoryUri)
        .putString(BOOTSTRAP_DOWNLOAD_DIRECTORY_LABEL_KEY, normalizedSnapshot.downloadDirectoryLabel)
        .putString(
            BOOTSTRAP_DOWNLOAD_FILE_NAME_TEMPLATE_KEY,
            normalizedSnapshot.downloadFileNameTemplate
        )
        .apply()
}

internal fun Preferences.toBootstrapSettingsSnapshot(): BootstrapSettingsSnapshot {
    return BootstrapSettingsSnapshot(
        bypassProxy = this[SettingsKeys.BYPASS_PROXY] ?: true,
        downloadDirectoryUri = this[SettingsKeys.DOWNLOAD_DIRECTORY_URI],
        downloadDirectoryLabel = this[SettingsKeys.DOWNLOAD_DIRECTORY_LABEL],
        downloadFileNameTemplate = this[SettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE]
    ).sanitized()
}

private fun readCachedBootstrapSettingsSnapshot(context: Context): BootstrapSettingsSnapshot? {
    val prefs = context.getSharedPreferences(BOOTSTRAP_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(BOOTSTRAP_SNAPSHOT_READY_KEY, false)) {
        return null
    }
    return BootstrapSettingsSnapshot(
        bypassProxy = prefs.getBoolean(BOOTSTRAP_BYPASS_PROXY_KEY, true),
        downloadDirectoryUri = prefs.getString(BOOTSTRAP_DOWNLOAD_DIRECTORY_URI_KEY, null),
        downloadDirectoryLabel = prefs.getString(BOOTSTRAP_DOWNLOAD_DIRECTORY_LABEL_KEY, null),
        downloadFileNameTemplate = prefs.getString(BOOTSTRAP_DOWNLOAD_FILE_NAME_TEMPLATE_KEY, null)
    ).sanitized()
}
