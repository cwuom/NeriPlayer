package moe.ouom.neriplayer.core.download.storage.queue

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File

/**
 * reads the v15 files kept only for one-time bootstrap
 *
 * New queue and cancellation state is owned by download_operation. This
 * object intentionally has no writer or mutation API
 */
internal object ManagedDownloadQueueStore {
    private const val TAG = "ManagedDownloadStorage"

    fun readPendingDownloadQueueFile(
        queueFile: File
    ): List<ManagedDownloadStorage.PendingDownloadQueueEntry>? {
        if (!queueFile.isFile) return null
        val rawPayload = runCatching {
            queueFile.readText(Charsets.UTF_8).takeIf(String::isNotBlank)
        }.onFailure {
            NPLogger.w(TAG, "读取旧未完成下载队列失败: ${it.message}")
        }.getOrNull() ?: return null

        return runCatching {
            ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload(rawPayload)
        }.onFailure {
            NPLogger.w(TAG, "解析旧未完成下载队列失败: ${it.message}")
        }.getOrNull()
    }

    fun readCancelledDownloadKeysFile(keysFile: File): Set<String>? {
        if (!keysFile.isFile) return null
        val rawPayload = runCatching {
            keysFile.readText(Charsets.UTF_8).takeIf(String::isNotBlank)
        }.onFailure {
            NPLogger.w(TAG, "读取旧已取消下载标记失败: ${it.message}")
        }.getOrNull() ?: return null

        return runCatching {
            ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(rawPayload)
        }.onFailure {
            NPLogger.w(TAG, "解析旧已取消下载标记失败: ${it.message}")
        }.getOrNull()
    }
}
