package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import androidx.core.content.edit
import moe.ouom.neriplayer.core.download.DownloadClearVisibility
import moe.ouom.neriplayer.core.logging.NPLogger

/** 保存清空阶段的轻量进度，进程被回收后可继续展示和收敛 */
internal object PersistentDownloadClearProgressStore {
    private const val TAG = "NERI-DownloadClearProgress"
    private const val PREFERENCES_NAME = "download_clear_progress_v1"
    private const val PHASE_KEY = "phase"
    private const val COMPLETED_STEPS_KEY = "completed_steps"
    private const val AFFECTED_ITEM_COUNT_KEY = "affected_item_count"
    private const val FAILED_ITEM_COUNT_KEY = "failed_item_count"
    private const val COMPLETED_ITEM_COUNT_KEY = "completed_item_count"
    private const val TOTAL_ITEM_COUNT_KEY = "total_item_count"

    fun save(
        context: Context,
        progress: DownloadClearVisibility.ClearProgress
    ) {
        runCatching {
            // 清空流程可能在下一行就被 LMK 终止，进度必须在返回前落盘
            preferences(context).edit(commit = true) {
                putString(PHASE_KEY, progress.phase.name)
                    .putInt(COMPLETED_STEPS_KEY, progress.completedSteps)
                    .putInt(AFFECTED_ITEM_COUNT_KEY, progress.affectedItemCount)
                    .putInt(FAILED_ITEM_COUNT_KEY, progress.failedItemCount)
                    .putInt(COMPLETED_ITEM_COUNT_KEY, progress.completedItemCount)
                    .putInt(TOTAL_ITEM_COUNT_KEY, progress.totalItemCount)
            }
        }.onFailure { error ->
            NPLogger.w(TAG, "保存下载清空进度失败: ${error.message}")
        }
    }

    fun read(context: Context): DownloadClearVisibility.ClearProgress? {
        return runCatching {
            val preferences = preferences(context)
            val phase = preferences.getString(PHASE_KEY, null)
                ?.let { value ->
                    runCatching { DownloadClearVisibility.ClearPhase.valueOf(value) }
                        .getOrNull()
                }
                ?: return@runCatching null
            DownloadClearVisibility.ClearProgress(
                phase = phase,
                completedSteps = preferences.getInt(COMPLETED_STEPS_KEY, 0),
                totalSteps = 4,
                affectedItemCount = preferences.getInt(AFFECTED_ITEM_COUNT_KEY, 0),
                failedItemCount = preferences.getInt(FAILED_ITEM_COUNT_KEY, 0),
                completedItemCount = preferences.getInt(COMPLETED_ITEM_COUNT_KEY, 0),
                totalItemCount = preferences.getInt(TOTAL_ITEM_COUNT_KEY, 0)
            )
        }.onFailure { error ->
            NPLogger.w(TAG, "读取下载清空进度失败: ${error.message}")
        }.getOrNull()
    }

    fun clear(context: Context) {
        runCatching {
            // 清理完成后进程可能立即被回收，异步 edit 会让旧进度在下次启动复活
            preferences(context).edit(commit = true) { clear() }
        }.onFailure { error ->
            NPLogger.w(TAG, "清除下载清空进度失败: ${error.message}")
        }
    }

    private fun preferences(context: Context) = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
