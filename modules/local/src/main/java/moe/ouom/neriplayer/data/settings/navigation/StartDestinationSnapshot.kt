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
import android.content.SharedPreferences
import android.os.Looper
import androidx.core.content.edit
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.settings.navigation.DEFAULT_START_DESTINATION
import moe.ouom.neriplayer.data.model.settings.navigation.StartDestinationSnapshot
import moe.ouom.neriplayer.data.settings.SettingsKeys
import moe.ouom.neriplayer.data.settings.dataStore

private const val START_DESTINATION_SNAPSHOT_PREFS = "start_destination_snapshot_cache"
private const val START_DESTINATION_KEY = "start_destination"
private val startDestinationSnapshotWarmupScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)
private val startDestinationSnapshotLock = Any()
private val startDestinationSettingsMutex = Mutex()

/**
 * 串行执行设置变更和真值刷新，避免旧观察结果覆盖更新后的缓存
 * 等待和变更可取消，变更结束后仍完成缓存对账，刷新失败时保留缓存并向外抛错
 * updateSettings 只能执行底层设置写入，不能重入此函数；已有变更异常优先于刷新异常
 */
suspend fun synchronizeStartDestinationSnapshot(
    context: Context,
    updateSettings: suspend () -> Unit = {}
) {
    startDestinationSettingsMutex.withLock {
        currentCoroutineContext().ensureActive()
        var updateFailure: Throwable? = null
        try {
            updateSettings()
        } catch (failure: Throwable) {
            updateFailure = failure
            throw failure
        } finally {
            try {
                withContext(NonCancellable) {
                    val snapshot = context.dataStore.data.first().toStartDestinationSnapshot()
                    persistStartDestinationSnapshot(context, snapshot)
                }
            } catch (refreshFailure: Exception) {
                val originalFailure = updateFailure
                if (originalFailure == null) throw refreshFailure
                if (refreshFailure !== originalFailure) originalFailure.addSuppressed(refreshFailure)
            }
        }
    }
}

/**
 * 读取启动页偏好的同步快照.
 *
 * 返回 `null` 表示缓存尚未就绪, 调用方必须保留"未知"状态, 不能把默认值当作已确定的选择.
 * 主线程上不做阻塞读, 只调度后台补热; 后台线程可阻塞读一次真值并落盘.
 *
 * @return 已确定的快照; 缓存缺失且当前无法读到真值时为 `null`.
 */
fun readStartDestinationSnapshotOrNull(context: Context): StartDestinationSnapshot? {
    readCachedStartDestinationSnapshot(context)?.let { return it }

    if (Looper.myLooper() == Looper.getMainLooper()) {
        warmStartDestinationSnapshot(context)
        return null
    }

    val snapshot = runCatching {
        runBlocking {
            context.dataStore.data.first().toStartDestinationSnapshot()
        }
    }.getOrNull() ?: return readCachedStartDestinationSnapshot(context)

    return initializeStartDestinationSnapshotIfMissing(context, snapshot)
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
            initializeStartDestinationSnapshotIfMissing(appContext, snapshot)
        }
    }
}

/**
 * 只初始化仍缺失的缓存，避免较慢的读取覆盖期间写透的新选择
 * 返回锁内实际采用的快照，已有缓存优先于传入的读取结果
 */
private fun initializeStartDestinationSnapshotIfMissing(
    context: Context,
    snapshot: StartDestinationSnapshot
): StartDestinationSnapshot {
    val prefs = context.getSharedPreferences(START_DESTINATION_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    return synchronized(startDestinationSnapshotLock) {
        // 读取期间可能已有设置写透，补热不能覆盖更新后的选择
        readCachedStartDestinationSnapshot(prefs) ?: snapshot.also { initialSnapshot ->
            prefs.edit { putString(START_DESTINATION_KEY, initialSnapshot.startDestination) }
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
 * 发布已确认的启动页快照，只负责短缓存临界区
 * 设置写入和观察入口须通过 [synchronizeStartDestinationSnapshot] 协调真值读取顺序
 */
fun persistStartDestinationSnapshot(
    context: Context,
    snapshot: StartDestinationSnapshot
) {
    val prefs = context.getSharedPreferences(START_DESTINATION_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    synchronized(startDestinationSnapshotLock) {
        prefs.edit { putString(START_DESTINATION_KEY, snapshot.startDestination) }
    }
}

/**
 * 只读缓存, 不做任何 DataStore 访问, 也不会触发补热.
 *
 * @return 缓存命中时的快照; 未写入过该键时为 `null`, 用于区分"未知"与"已确定为默认值".
 */
internal fun readCachedStartDestinationSnapshot(context: Context): StartDestinationSnapshot? {
    val prefs = context.getSharedPreferences(START_DESTINATION_SNAPSHOT_PREFS, Context.MODE_PRIVATE)
    return synchronized(startDestinationSnapshotLock) {
        readCachedStartDestinationSnapshot(prefs)
    }
}

/**
 * 从同一缓存句柄区分未知状态与已保存的默认路由
 * 调用方须持有缓存锁，保证键存在性检查和取值不会被写入打断
 */
private fun readCachedStartDestinationSnapshot(prefs: SharedPreferences): StartDestinationSnapshot? {
    if (!prefs.contains(START_DESTINATION_KEY)) {
        return null
    }
    return StartDestinationSnapshot(
        startDestination = prefs.getString(START_DESTINATION_KEY, DEFAULT_START_DESTINATION)
            ?: DEFAULT_START_DESTINATION
    )
}
