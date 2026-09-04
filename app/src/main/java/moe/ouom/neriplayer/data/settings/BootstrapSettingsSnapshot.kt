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
import android.content.SharedPreferences
import android.os.Looper
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.core.download.normalizeDownloadFileNameTemplate
import moe.ouom.neriplayer.core.player.download.DEFAULT_DOWNLOAD_PARALLELISM
import moe.ouom.neriplayer.core.player.download.normalizeDownloadParallelism
import java.util.concurrent.atomic.AtomicBoolean

private const val BOOTSTRAP_SNAPSHOT_PREFS = "bootstrap_settings_snapshot"
private const val BOOTSTRAP_SNAPSHOT_READY_KEY = "ready"
private const val BOOTSTRAP_BYPASS_PROXY_KEY = "bypass_proxy"
private const val BOOTSTRAP_YOUTUBE_ENABLED_KEY = "youtube_enabled"
private const val BOOTSTRAP_PREFER_HIGH_REFRESH_RATE_KEY = "prefer_high_refresh_rate"
private const val BOOTSTRAP_DOWNLOAD_DIRECTORY_URI_KEY = "download_directory_uri"
private const val BOOTSTRAP_DOWNLOAD_DIRECTORY_LABEL_KEY = "download_directory_label"
private const val BOOTSTRAP_DOWNLOAD_FILE_NAME_TEMPLATE_KEY = "download_file_name_template"
private const val BOOTSTRAP_DOWNLOAD_FOLLOW_PLAYBACK_AUDIO_QUALITY_KEY =
    "download_follow_playback_audio_quality"
private const val BOOTSTRAP_DOWNLOAD_PARALLELISM_KEY = "download_parallelism"
private val bootstrapSnapshotWarmupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val bootstrapSnapshotWarmupRunning = AtomicBoolean(false)
private val bootstrapSnapshotPersistenceLock = Any()

data class BootstrapSettingsSnapshot(
    val bypassProxy: Boolean = true,
    val youtubeEnabled: Boolean = true,
    val preferHighRefreshRate: Boolean = false,
    val downloadDirectoryUri: String? = null,
    val downloadDirectoryLabel: String? = null,
    val downloadFileNameTemplate: String? = null,
    val downloadFollowPlaybackAudioQuality: Boolean = true,
    val downloadParallelism: Int = DEFAULT_DOWNLOAD_PARALLELISM
) {
    fun sanitized(): BootstrapSettingsSnapshot {
        return copy(
            downloadDirectoryUri = downloadDirectoryUri?.takeIf { it.isNotBlank() },
            downloadDirectoryLabel = downloadDirectoryLabel?.takeIf { it.isNotBlank() },
            downloadFileNameTemplate = normalizeDownloadFileNameTemplate(downloadFileNameTemplate),
            downloadParallelism = normalizeDownloadParallelism(downloadParallelism)
        )
    }
}

fun readBootstrapSettingsSnapshotSync(context: Context): BootstrapSettingsSnapshot {
    readCachedBootstrapSettingsSnapshot(context)?.let { snapshot ->
        if (hasCompleteBootstrapSettingsSnapshot(context)) {
            return snapshot
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            warmBootstrapSettingsSnapshot(context)
            return snapshot
        }
    }

    if (Looper.myLooper() == Looper.getMainLooper()) {
        warmBootstrapSettingsSnapshot(context)
        return BootstrapSettingsSnapshot()
    }

    return runCatching {
        runBlocking {
            context.dataStore.data.first().toBootstrapSettingsSnapshot()
        }
    }.getOrElse {
        BootstrapSettingsSnapshot()
    }.also { snapshot ->
        persistBootstrapSettingsSnapshot(context, snapshot)
    }
}

internal fun warmBootstrapSettingsSnapshot(context: Context) {
    if (!bootstrapSnapshotWarmupRunning.compareAndSet(false, true)) return
    val appContext = context.applicationContext
    bootstrapSnapshotWarmupScope.launch {
        try {
            val snapshot = appContext.dataStore.data.first().toBootstrapSettingsSnapshot()
            persistWarmedBootstrapSettingsSnapshot(appContext, snapshot)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // a later caller can retry after a transient DataStore failure
        } finally {
            bootstrapSnapshotWarmupRunning.set(false)
        }
    }
}

internal fun persistWarmedBootstrapSettingsSnapshot(
    context: Context,
    snapshot: BootstrapSettingsSnapshot
): Boolean {
    return synchronized(bootstrapSnapshotPersistenceLock) {
        if (hasCompleteBootstrapSettingsSnapshot(context)) {
            false
        } else {
            persistBootstrapSettingsSnapshot(context, snapshot)
            true
        }
    }
}

internal suspend fun updateBootstrapSettingsSnapshot(
    context: Context,
    transform: (BootstrapSettingsSnapshot) -> BootstrapSettingsSnapshot
) {
    val currentSnapshot = readCachedBootstrapSettingsSnapshot(context)
        ?.takeIf { hasCompleteBootstrapSettingsSnapshot(context) }
        ?: context.dataStore.data.first().toBootstrapSettingsSnapshot()
    persistBootstrapSettingsSnapshot(context, transform(currentSnapshot))
}

internal fun persistBootstrapSettingsSnapshot(
    context: Context,
    snapshot: BootstrapSettingsSnapshot
) {
    synchronized(bootstrapSnapshotPersistenceLock) {
        val normalizedSnapshot = snapshot.sanitized()
        context.getSharedPreferences(BOOTSTRAP_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
            .edit {
                putBoolean(BOOTSTRAP_SNAPSHOT_READY_KEY, true)
                    .putBoolean(BOOTSTRAP_BYPASS_PROXY_KEY, normalizedSnapshot.bypassProxy)
                    .putBoolean(BOOTSTRAP_YOUTUBE_ENABLED_KEY, normalizedSnapshot.youtubeEnabled)
                    .putBoolean(
                        BOOTSTRAP_PREFER_HIGH_REFRESH_RATE_KEY,
                        normalizedSnapshot.preferHighRefreshRate
                    )
                    .putString(
                        BOOTSTRAP_DOWNLOAD_DIRECTORY_URI_KEY,
                        normalizedSnapshot.downloadDirectoryUri
                    )
                    .putString(
                        BOOTSTRAP_DOWNLOAD_DIRECTORY_LABEL_KEY,
                        normalizedSnapshot.downloadDirectoryLabel
                    )
                    .putString(
                        BOOTSTRAP_DOWNLOAD_FILE_NAME_TEMPLATE_KEY,
                        normalizedSnapshot.downloadFileNameTemplate
                    )
                    .putBoolean(
                        BOOTSTRAP_DOWNLOAD_FOLLOW_PLAYBACK_AUDIO_QUALITY_KEY,
                        normalizedSnapshot.downloadFollowPlaybackAudioQuality
                    )
                    .putInt(
                        BOOTSTRAP_DOWNLOAD_PARALLELISM_KEY,
                        normalizedSnapshot.downloadParallelism
                    )
            }
    }
}

internal fun updateBootstrapDownloadFollowPlaybackAudioQuality(
    context: Context,
    followsPlaybackQuality: Boolean
) {
    val preferences = context.applicationContext.getSharedPreferences(
        BOOTSTRAP_SNAPSHOT_PREFS,
        Context.MODE_PRIVATE
    )
    if (!preferences.getBoolean(BOOTSTRAP_SNAPSHOT_READY_KEY, false)) {
        return
    }
    preferences.edit {
        putBoolean(
            BOOTSTRAP_DOWNLOAD_FOLLOW_PLAYBACK_AUDIO_QUALITY_KEY,
            followsPlaybackQuality
        )
    }
}

internal fun Preferences.toBootstrapSettingsSnapshot(): BootstrapSettingsSnapshot {
    return BootstrapSettingsSnapshot(
        bypassProxy = this[SettingsKeys.BYPASS_PROXY] ?: true,
        youtubeEnabled = this[SettingsKeys.YOUTUBE_ENABLED] ?: true,
        preferHighRefreshRate = this[SettingsKeys.PREFER_HIGH_REFRESH_RATE] ?: false,
        downloadDirectoryUri = this[SettingsKeys.DOWNLOAD_DIRECTORY_URI],
        downloadDirectoryLabel = this[SettingsKeys.DOWNLOAD_DIRECTORY_LABEL],
        downloadFileNameTemplate = this[SettingsKeys.DOWNLOAD_FILE_NAME_TEMPLATE],
        downloadFollowPlaybackAudioQuality = valueOf(
            AutoSettingsSchema.download.downloadFollowPlaybackAudioQuality
        ),
        downloadParallelism = valueOf(AutoSettingsSchema.download.downloadParallelism)
    ).sanitized()
}

internal fun readBootstrapDownloadParallelism(context: Context): Int? {
    val preferences = context.applicationContext.getSharedPreferences(
        BOOTSTRAP_SNAPSHOT_PREFS,
        Context.MODE_PRIVATE
    )
    return readCachedBootstrapDownloadParallelism(preferences)
}

private fun hasCompleteBootstrapSettingsSnapshot(context: Context): Boolean {
    val preferences = context.applicationContext.getSharedPreferences(
        BOOTSTRAP_SNAPSHOT_PREFS,
        Context.MODE_PRIVATE
    )
    return preferences.contains(BOOTSTRAP_DOWNLOAD_FOLLOW_PLAYBACK_AUDIO_QUALITY_KEY) &&
        readCachedBootstrapDownloadParallelism(preferences) != null
}

private fun readCachedBootstrapDownloadParallelism(preferences: SharedPreferences): Int? {
    if (!preferences.getBoolean(BOOTSTRAP_SNAPSHOT_READY_KEY, false) ||
        !preferences.contains(BOOTSTRAP_DOWNLOAD_PARALLELISM_KEY)
    ) {
        return null
    }
    return runCatching {
        normalizeDownloadParallelism(
            preferences.getInt(
                BOOTSTRAP_DOWNLOAD_PARALLELISM_KEY,
                DEFAULT_DOWNLOAD_PARALLELISM
            )
        )
    }.getOrNull()
}

private fun readCachedBootstrapSettingsSnapshot(context: Context): BootstrapSettingsSnapshot? {
    val prefs = context.getSharedPreferences(BOOTSTRAP_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    if (!prefs.getBoolean(BOOTSTRAP_SNAPSHOT_READY_KEY, false)) {
        return null
    }
    val downloadParallelism = readCachedBootstrapDownloadParallelism(prefs)
        ?: DEFAULT_DOWNLOAD_PARALLELISM
    return BootstrapSettingsSnapshot(
        bypassProxy = prefs.getBoolean(BOOTSTRAP_BYPASS_PROXY_KEY, true),
        youtubeEnabled = prefs.getBoolean(BOOTSTRAP_YOUTUBE_ENABLED_KEY, true),
        preferHighRefreshRate = prefs.getBoolean(
            BOOTSTRAP_PREFER_HIGH_REFRESH_RATE_KEY,
            false
        ),
        downloadDirectoryUri = prefs.getString(BOOTSTRAP_DOWNLOAD_DIRECTORY_URI_KEY, null),
        downloadDirectoryLabel = prefs.getString(BOOTSTRAP_DOWNLOAD_DIRECTORY_LABEL_KEY, null),
        downloadFileNameTemplate = prefs.getString(BOOTSTRAP_DOWNLOAD_FILE_NAME_TEMPLATE_KEY, null),
        downloadFollowPlaybackAudioQuality = prefs.getBoolean(
            BOOTSTRAP_DOWNLOAD_FOLLOW_PLAYBACK_AUDIO_QUALITY_KEY,
            true
        ),
        downloadParallelism = downloadParallelism
    ).sanitized()
}
