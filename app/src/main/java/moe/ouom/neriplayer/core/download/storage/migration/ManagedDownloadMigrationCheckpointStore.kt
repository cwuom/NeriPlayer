package moe.ouom.neriplayer.core.download.storage.migration

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

internal class ManagedDownloadMigrationCheckpointStore internal constructor(
    private val preferences: SharedPreferences
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE
        )
    )

    fun readMinimumAudioCount(workId: String): Int {
        return runCatching {
            preferences.getInt(keyFor(workId), 0)
        }.getOrDefault(0).coerceAtLeast(0)
    }

    // KTX edit discards commit's boolean result, which is required for retry decisions
    @SuppressLint("UseKtx")
    fun recordMinimumAudioCount(workId: String, minimumAudioCount: Int): Int {
        val persistedCount = readMinimumAudioCount(workId)
        val resolvedCount = maxOf(persistedCount, minimumAudioCount).coerceAtLeast(0)
        val committed = preferences.edit()
            .putInt(keyFor(workId), resolvedCount)
            .commit()
        if (!committed) {
            throw ManagedDownloadMigrationException.transient(
                "无法持久化迁移文件下界"
            )
        }
        return resolvedCount
    }

    @SuppressLint("UseKtx")
    fun clear(workId: String): Boolean {
        return preferences.edit()
            .remove(keyFor(workId))
            .commit()
    }

    private fun keyFor(workId: String): String = "$KEY_PREFIX$workId"

    companion object {
        internal const val PREFERENCES_NAME = "managed_download_migration_checkpoint"
        internal const val KEY_PREFIX = "minimum_audio_count:"
    }
}
