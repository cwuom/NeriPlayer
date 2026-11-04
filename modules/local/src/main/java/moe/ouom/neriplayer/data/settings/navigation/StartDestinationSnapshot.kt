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
 * 读取启动页偏好的同步快照.
 *
 * 返回 `null` 表示缓存尚未就绪, 调用方必须保留"未知"状态, 不能把默认值当作已确定的选择.
 * 主线程上不做阻塞读, 只调度后台补热; 后台线程可阻塞读一次真值并落盘.
 *
 * @return 已确定的快照; 缓存缺失且无法在当前线程阻塞读取时为 `null`.
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

/**
 * 在 IO 线程上补热快照, 供主线程首帧之后的下一次启动使用.
 *
 * 读取失败时保持静默, 让调用方继续走"未知"路径, 而不是写入一个可能是错的默认值.
 */
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

/** 把 DataStore 偏好投影成启动页快照, 未设置时回退到 [DEFAULT_START_DESTINATION]. */
fun Preferences.toStartDestinationSnapshot(): StartDestinationSnapshot {
    return StartDestinationSnapshot(
        startDestination = this[SettingsKeys.DEFAULT_START_DESTINATION] ?: DEFAULT_START_DESTINATION
    )
}

/**
 * 写入启动页快照.
 *
 * 所有改动 `default_start_destination` 的入口都必须调用它, 否则下一次冷启动会读到过期值.
 */
fun persistStartDestinationSnapshot(
    context: Context,
    snapshot: StartDestinationSnapshot
) {
    context.getSharedPreferences(START_DESTINATION_SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit {
        putString(START_DESTINATION_KEY, snapshot.startDestination)
    }
}

/**
 * 只读缓存, 不做任何 DataStore 访问, 也不会触发补热.
 *
 * @return 缓存命中时的快照; 未写入过该键时为 `null`, 用于区分"未知"与"已确定为默认值".
 */
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
