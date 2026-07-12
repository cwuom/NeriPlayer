package moe.ouom.neriplayer.core.download.storage.snapshot

import android.content.Context
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.SNAPSHOT_CACHE_FILE_NAME
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.io.IOException

internal object ManagedDownloadSnapshotDiskCache {
    private const val TAG = "ManagedDownloadStorage"

    fun cacheFile(context: Context): File {
        return File(context.filesDir, SNAPSHOT_CACHE_FILE_NAME)
    }

    fun persist(
        context: Context,
        cacheKey: String,
        snapshot: ManagedDownloadStorage.DownloadLibrarySnapshot
    ) {
        runCatching {
            cacheFile(context).writeText(
                ManagedDownloadSnapshotIndex.serializePayload(cacheKey, snapshot),
                Charsets.UTF_8
            )
        }.onFailure {
            NPLogger.w(TAG, "写入下载索引缓存失败: ${it.message}")
        }
    }

    fun restore(
        context: Context,
        expectedKey: String? = null
    ): Pair<String, ManagedDownloadStorage.DownloadLibrarySnapshot>? {
        val rawPayload = runCatching {
            cacheFile(context).takeIf(File::exists)?.readText(Charsets.UTF_8)
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
        runCatching {
            val cacheFile = cacheFile(context)
            if (cacheFile.exists() && !cacheFile.delete()) {
                throw IOException("无法删除旧下载索引缓存")
            }
        }.onFailure {
            NPLogger.w(TAG, "清理下载索引缓存失败: ${it.message}")
        }
    }
}
