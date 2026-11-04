package moe.ouom.neriplayer.data.settings.navigation

/*
 * NeriPlayer - A unified Android player for streaming music and videos from multiple online platforms.
 * Copyright (C) 2025-2025 NeriPlayer developers
 * https://github.com/cwuom/NeriPlayer
 *
 * This software is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
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
 * File: moe.ouom.neriplayer.data.settings.navigation/StartDestinationSnapshot
 * Updated: 2026/11/4
 */

import android.content.Context
import android.os.Looper
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.model.settings.navigation.DEFAULT_START_DESTINATION
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.dataStore

private const val START_DESTINATION_SNAPSHOT_PREFS = "start_destination_snapshot_cache"
private const val START_DESTINATION_KEY = "start_destination"
private val startDestinationSnapshotWarmupScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * 启动页偏好在 DataStore 之外另存一份同步镜像, 让首帧就能拿到确定值.
 * 返回 null 表示缓存尚未就绪, 调用方必须保留"未知"状态, 不能把默认值当作已确定的选择.
 * 主线程上不做阻塞读, 只调度后台补热; 后台线程可阻塞读一次真值.
 */
fun readStartDestinationSnapshotOrNull(context: Context): StartDestinationSnapshot? {
    readCachedStartDestinationSnapshot(context)?.let { return it }

    if (Looper.myLooper() == Looper.getMainLooper()) {
        warmStartDestinationSnapshot(context)
        return null
    }

    return runCatching {
        runBlocking {
            context.dataStore.data.first().toStartDestinationSnapshot()
        }
    }.getOrElse {
        StartDestinationSnapshot()
    }.also { snapshot ->
        persistStartDestinationSnapshot(context, snapshot)
    }
}

private fun warmStartDestinationSnapshot(context: Context) {
    val appContext = context.applicationContext
    startDestinationSnapshotWarmupScope.launch {
        runCatching {
            appContext.dataStore.data.first().toStartDestinationSnapshot()
        }.onSuccess { snapshot ->
            persistStartDestinationSnapshot(appContext, snapshot)
        }
    }
}

fun Preferences.toStartDestinationSnapshot(): StartDestinationSnapshot {
    return StartDestinationSnapshot(
        startDestination = this[SettingsKeys.DEFAULT_START_DESTINATION] ?: DEFAULT_START_DESTINATION
    )
}

fun persistStartDestinationSnapshot(
    context: Context,
    snapshot: StartDestinationSnapshot
) {
    context.getSharedPreferences(START_DESTINATION_SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit {
        putString(START_DESTINATION_KEY, snapshot.startDestination)
    }
}

internal fun readCachedStartDestinationSnapshot(context: Context): StartDestinationSnapshot? {
    val prefs = context.getSharedPreferences(START_DESTINATION_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    if (!prefs.contains(START_DESTINATION_KEY)) {
        return null
    }
    return StartDestinationSnapshot(
        startDestination = prefs.getString(START_DESTINATION_KEY, DEFAULT_START_DESTINATION)
            ?: DEFAULT_START_DESTINATION
    )
}
