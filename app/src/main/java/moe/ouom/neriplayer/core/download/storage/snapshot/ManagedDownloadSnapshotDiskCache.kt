package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_FILE_NAME
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.io.IOException

internal const val CURRENT_SNAPSHOT_CACHE_FILE_NAME = "managed_download_snapshot_current_v1.json"

internal object ManagedDownloadSnapshotDiskCache {
    private const val TAG = "ManagedDownloadStorage"

    fun cacheFile(context: Context): File {
        return File(context.filesDir, CURRENT_SNAPSHOT_CACHE_FILE_NAME)
    }

    fun restore(
        context: Context,
        expectedKey: String? = null
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        val rawPayload = runCatching {
            val currentFile = cacheFile(context)
            // 当前文件存在时不回退旧快照，避免损坏或根目录切换后复活过期索引
            val sourceFile = if (currentFile.exists()) {
                currentFile
            } else {
                File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
            }
            sourceFile.takeIf(File::exists)?.readText(Charsets.UTF_8)
        }.onFailure {
            NPLogger.w(TAG, "读取下载索引缓存失败: ${it.message}")
        }.getOrNull() ?: return null

        return runCatching {
            ManagedDownloadSnapshotIndex.deserializePayload(rawPayload, expectedKey)
        }.onFailure {
            NPLogger.w(TAG, "解析下载索引缓存失败: ${it.message}")
        }.getOrNull()
    }

    fun delete(context: Context) {
        // 显式失效同时清理兼容文件，避免下次冷启动重新读到旧代次
        listOf(cacheFile(context), File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)).forEach { file ->
            runCatching {
                if (file.exists() && !file.delete()) {
                    throw IOException("无法删除下载索引缓存: ${file.name}")
                }
            }.onFailure {
                NPLogger.w(TAG, "清理下载索引缓存失败: ${it.message}")
            }
        }
    }
}
