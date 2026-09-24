package moe.ouom.neriplayer.core.download.catalog

import android.content.Context
import moe.ouom.neriplayer.core.logging.NPLogger

internal object DownloadedSongDeleteFailureDismissalStore {
    private const val TAG = "DownloadedSongDeleteFailureDismissal"
    private const val PREFERENCES = "downloaded_song_delete_feedback"
    private const val DISMISSED_KEY = "failure_dismissed"

    fun read(context: Context): Boolean = runCatching {
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean(DISMISSED_KEY, false)
    }.onFailure { error ->
        NPLogger.w(TAG, "读取删除失败横幅状态失败: ${error.message}", error)
    }.getOrDefault(false)

    fun write(context: Context, dismissed: Boolean) {
        runCatching {
            context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(DISMISSED_KEY, dismissed).apply()
        }.onFailure { error ->
            NPLogger.w(TAG, "保存删除失败横幅状态失败: ${error.message}", error)
        }
    }
}
