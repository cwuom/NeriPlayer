package moe.ouom.neriplayer.core.download.execution

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.atomic.AtomicLong
import moe.ouom.neriplayer.core.download.catalog.PersistentDownloadedSongDeleteIntentStore

internal interface DownloadClearFenceStore {
    fun isActive(context: Context): Boolean

    fun activate(context: Context): Boolean

    fun clear(context: Context): Boolean
}

/** 清空下载任务与删除整个下载库使用不同的进程死亡恢复策略 */
internal enum class DownloadClearPurpose {
    TASK_PROGRESS,
    FULL_LIBRARY_DELETE
}

internal enum class DownloadClearFenceReleaseResult {
    RELEASED,
    SUPERSEDED,
    FAILED
}

internal object PersistentDownloadClearFenceStore : DownloadClearFenceStore {
    private val clearRequestEpoch = AtomicLong(0L)
    private val clearedRequestEpoch = AtomicLong(0L)
    @Volatile
    private var requestedPurpose = DownloadClearPurpose.TASK_PROGRESS
    private val schedulingLock = Any()

    internal fun beginClear(
        purpose: DownloadClearPurpose = DownloadClearPurpose.TASK_PROGRESS
    ): Long {
        requestedPurpose = purpose
        return clearRequestEpoch.incrementAndGet()
    }

    internal fun activePurpose(context: Context): DownloadClearPurpose {
        return synchronized(schedulingLock) {
            try {
                val persistedPurpose = preferencesOrNull(context)?.getString(PURPOSE_KEY, null)
                    ?.let { value ->
                        runCatching { DownloadClearPurpose.valueOf(value) }.getOrNull()
                    }
                persistedPurpose
                    ?: PersistentDownloadedSongDeleteIntentStore.hasPending(context)
                        .takeIf { it }
                        ?.let { DownloadClearPurpose.FULL_LIBRARY_DELETE }
                    ?: DownloadClearPurpose.TASK_PROGRESS
            } catch (_: Throwable) {
                // 读取失败时保守保留 catalog，避免把已下载歌曲误判为已删除
                DownloadClearPurpose.TASK_PROGRESS
            }
        }
    }

    override fun isActive(context: Context): Boolean {
        if (isClearRequested()) {
            return true
        }
        return synchronized(schedulingLock) {
            isPersistedFenceActive(context) ||
                PersistentDownloadedSongDeleteIntentStore.hasPending(context)
        }
    }

    internal fun <T> withSchedulingPermit(
        context: Context,
        onFenceActive: () -> T,
        schedule: () -> T
    ): T {
        return synchronized(schedulingLock) {
            if (
                isClearRequested() ||
                    isPersistedFenceActive(context) ||
                    PersistentDownloadedSongDeleteIntentStore.hasPending(context)
            ) {
                onFenceActive()
            } else {
                schedule()
            }
        }
    }

    override fun activate(context: Context): Boolean {
        ensureClearRequested()
        return synchronized(schedulingLock) {
            try {
                preferencesOrNull(context)?.let { preferences ->
                    commitFenceEdit(preferences) {
                        putBoolean(ACTIVE_KEY, true)
                        putLong(REQUESTED_AT_MS_KEY, System.currentTimeMillis())
                        putString(PURPOSE_KEY, requestedPurpose.name)
                    }
                } == true
            } catch (_: Throwable) {
                false
            }
        }
    }

    override fun clear(context: Context): Boolean {
        return clearIfCurrent(
            context = context,
            expectedEpoch = clearRequestEpoch.get()
        ) == DownloadClearFenceReleaseResult.RELEASED
    }

    internal fun clearIfCurrent(
        context: Context,
        expectedEpoch: Long
    ): DownloadClearFenceReleaseResult {
        return try {
            synchronized(schedulingLock) {
                if (clearRequestEpoch.get() != expectedEpoch) {
                    return@synchronized DownloadClearFenceReleaseResult.SUPERSEDED
                }
                val cleared = preferencesOrNull(context)?.let { preferences ->
                    commitFenceEdit(preferences) {
                        remove(ACTIVE_KEY)
                        remove(REQUESTED_AT_MS_KEY)
                        remove(PURPOSE_KEY)
                    }
                } == true
                if (cleared) {
                    clearedRequestEpoch.accumulateAndGet(expectedEpoch) { current, candidate ->
                        maxOf(current, candidate)
                    }
                    DownloadClearFenceReleaseResult.RELEASED
                } else {
                    DownloadClearFenceReleaseResult.FAILED
                }
            }
        } catch (_: Throwable) {
            DownloadClearFenceReleaseResult.FAILED
        }
    }

    private fun isClearRequested(): Boolean {
        return clearRequestEpoch.get() > clearedRequestEpoch.get()
    }

    private fun ensureClearRequested() {
        while (true) {
            val requested = clearRequestEpoch.get()
            if (requested > clearedRequestEpoch.get()) {
                return
            }
            if (clearRequestEpoch.compareAndSet(requested, requested + 1L)) {
                return
            }
        }
    }

    private fun isPersistedFenceActive(context: Context): Boolean {
        return try {
            preferencesOrNull(context)?.getBoolean(ACTIVE_KEY, false) ?: false
        } catch (_: Throwable) {
            // 读不到栅栏时先停住下载，等存储恢复后再继续
            true
        }
    }

    @SuppressLint("UseKtx")
    private fun commitFenceEdit(
        preferences: SharedPreferences,
        mutation: SharedPreferences.Editor.() -> Unit
    ): Boolean {
        // commit 返回成功才算栅栏已经写入，可供崩溃后恢复
        val editor = preferences.edit()
        editor.mutation()
        return editor.commit()
    }

    private fun preferencesOrNull(context: Context): SharedPreferences? {
        return context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    }

    private const val PREFERENCES_NAME = "download_clear_fence_v1"
    private const val ACTIVE_KEY = "active"
    private const val REQUESTED_AT_MS_KEY = "requested_at_ms"
    private const val PURPOSE_KEY = "purpose"
}
