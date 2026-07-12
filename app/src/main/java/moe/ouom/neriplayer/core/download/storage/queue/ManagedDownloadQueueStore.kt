package moe.ouom.neriplayer.core.download.storage.queue

import moe.ouom.neriplayer.core.download.ManagedDownloadStorage
import moe.ouom.neriplayer.core.download.storage.CANCELLED_DOWNLOAD_KEYS_FILE_NAME
import moe.ouom.neriplayer.core.download.storage.ManagedDownloadStorageJsonCodec
import moe.ouom.neriplayer.core.download.storage.PENDING_DOWNLOAD_QUEUE_FILE_NAME
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import moe.ouom.neriplayer.core.logging.NPLogger
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicLong

internal object ManagedDownloadQueueStore {
    private const val TAG = "ManagedDownloadStorage"
    private val pendingDownloadQueueLock = Any()
    private val cancelledDownloadKeysLock = Any()
    private val atomicFileWriteIdGenerator = AtomicLong(0L)

    fun upsertPendingDownloadQueueInFile(
        queueFile: File,
        songs: List<SongItem>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val distinctSongs = songs.distinctBy { it.stableKey() }
        if (distinctSongs.isEmpty()) {
            return
        }
        synchronized(pendingDownloadQueueLock) {
            val existingEntries = readPendingDownloadQueueFile(queueFile)
            val mergedEntries = mergePendingDownloadQueueEntries(
                existingEntries = existingEntries,
                songs = distinctSongs,
                nowMs = nowMs
            )
            writePendingDownloadQueueFile(queueFile, mergedEntries, nowMs)
        }
    }

    fun listPendingQueuedDownloadsFromFile(
        queueFile: File
    ): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        return synchronized(pendingDownloadQueueLock) {
            readPendingDownloadQueueFile(queueFile)
        }
    }

    fun removePendingDownloadQueueEntriesFromFile(
        queueFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        synchronized(pendingDownloadQueueLock) {
            val existingEntries = readPendingDownloadQueueFile(queueFile)
            if (existingEntries.isEmpty()) {
                return
            }
            val retainedEntries = existingEntries.filterNot { it.stableKey in keys }
            if (retainedEntries.size == existingEntries.size) {
                return
            }
            writePendingDownloadQueueFile(queueFile, retainedEntries, nowMs)
        }
    }

    fun clearPendingDownloadQueueFile(
        queueFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(pendingDownloadQueueLock) {
            writePendingDownloadQueueFile(queueFile, emptyList(), nowMs)
        }
    }

    fun markCancelledDownloadKeysInFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        synchronized(cancelledDownloadKeysLock) {
            val mergedKeys = listCancelledDownloadKeysFromFileLocked(keysFile) + keys
            writeCancelledDownloadKeysFile(keysFile, mergedKeys, nowMs)
        }
    }

    fun listCancelledDownloadKeysFromFile(keysFile: File): Set<String> {
        return synchronized(cancelledDownloadKeysLock) {
            listCancelledDownloadKeysFromFileLocked(keysFile)
        }
    }

    fun removeCancelledDownloadKeysFromFile(
        keysFile: File,
        songKeys: Collection<String>,
        nowMs: Long = System.currentTimeMillis()
    ) {
        val keys = songKeys.filter(String::isNotBlank).toSet()
        if (keys.isEmpty()) {
            return
        }
        synchronized(cancelledDownloadKeysLock) {
            val retainedKeys = listCancelledDownloadKeysFromFileLocked(keysFile) - keys
            writeCancelledDownloadKeysFile(keysFile, retainedKeys, nowMs)
        }
    }

    fun clearCancelledDownloadKeysFile(
        keysFile: File,
        nowMs: Long = System.currentTimeMillis()
    ) {
        synchronized(cancelledDownloadKeysLock) {
            writeCancelledDownloadKeysFile(keysFile, emptySet(), nowMs)
        }
    }

    private fun mergePendingDownloadQueueEntries(
        existingEntries: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
        songs: List<SongItem>,
        nowMs: Long
    ): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        val merged = linkedMapOf<String, ManagedDownloadStorage.PendingDownloadQueueEntry>()
        existingEntries
            .sortedBy(ManagedDownloadStorage.PendingDownloadQueueEntry::order)
            .forEach { entry ->
                merged.putIfAbsent(entry.stableKey, entry)
            }

        var nextOrder = (merged.values.maxOfOrNull(ManagedDownloadStorage.PendingDownloadQueueEntry::order) ?: -1) + 1
        songs.forEach { song ->
            val stableKey = song.stableKey()
            val existingEntry = merged[stableKey]
            merged[stableKey] = ManagedDownloadStorage.PendingDownloadQueueEntry(
                stableKey = stableKey,
                song = song,
                order = existingEntry?.order ?: nextOrder++,
                queuedAtMs = existingEntry?.queuedAtMs ?: nowMs
            )
        }

        return merged.values
            .sortedBy(ManagedDownloadStorage.PendingDownloadQueueEntry::order)
            .mapIndexed { index, entry -> entry.copy(order = index) }
    }

    private fun readPendingDownloadQueueFile(
        queueFile: File
    ): List<ManagedDownloadStorage.PendingDownloadQueueEntry> {
        val rawPayload = runCatching {
            queueFile.takeIf(File::exists)
                ?.readText(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
        }.onFailure {
            NPLogger.w(TAG, "读取未完成下载队列失败: ${it.message}")
        }.getOrNull() ?: return emptyList()

        return runCatching {
            ManagedDownloadStorageJsonCodec.parsePendingDownloadQueuePayload(rawPayload)
        }.onFailure {
            NPLogger.w(TAG, "解析未完成下载队列失败: ${it.message}")
        }.getOrDefault(emptyList())
    }

    private fun writePendingDownloadQueueFile(
        queueFile: File,
        entries: List<ManagedDownloadStorage.PendingDownloadQueueEntry>,
        updatedAtMs: Long
    ) {
        if (entries.isEmpty()) {
            deletePendingDownloadQueueFile(queueFile)
            return
        }

        runCatching {
            writeTextAtomically(
                target = queueFile,
                content = ManagedDownloadStorageJsonCodec.serializePendingDownloadQueuePayload(entries, updatedAtMs)
            )
        }.onFailure {
            NPLogger.w(TAG, "写入未完成下载队列失败: ${it.message}")
        }
    }

    private fun deletePendingDownloadQueueFile(queueFile: File) {
        runCatching {
            if (queueFile.exists() && !queueFile.delete()) {
                throw IOException("delete failed: ${queueFile.name}")
            }
        }.onFailure {
            NPLogger.w(TAG, "删除未完成下载队列失败: ${it.message}")
        }
    }

    private fun listCancelledDownloadKeysFromFileLocked(keysFile: File): Set<String> {
        val rawPayload = runCatching {
            keysFile.takeIf(File::exists)
                ?.readText(Charsets.UTF_8)
                ?.takeIf(String::isNotBlank)
        }.onFailure {
            NPLogger.w(TAG, "读取已取消下载标记失败: ${it.message}")
        }.getOrNull() ?: return emptySet()

        return runCatching {
            ManagedDownloadStorageJsonCodec.parseCancelledDownloadKeysPayload(rawPayload)
        }.onFailure {
            NPLogger.w(TAG, "解析已取消下载标记失败: ${it.message}")
        }.getOrDefault(emptySet())
    }

    private fun writeCancelledDownloadKeysFile(
        keysFile: File,
        songKeys: Set<String>,
        updatedAtMs: Long
    ) {
        if (songKeys.isEmpty()) {
            deleteCancelledDownloadKeysFile(keysFile)
            return
        }

        runCatching {
            writeTextAtomically(
                target = keysFile,
                content = ManagedDownloadStorageJsonCodec.serializeCancelledDownloadKeysPayload(songKeys, updatedAtMs)
            )
        }.onFailure {
            NPLogger.w(TAG, "写入已取消下载标记失败: ${it.message}")
        }
    }

    private fun deleteCancelledDownloadKeysFile(keysFile: File) {
        runCatching {
            if (keysFile.exists() && !keysFile.delete()) {
                throw IOException("delete failed: ${keysFile.name}")
            }
        }.onFailure {
            NPLogger.w(TAG, "删除已取消下载标记失败: ${it.message}")
        }
    }

    private fun writeTextAtomically(target: File, content: String) {
        val parent = target.parentFile
        parent?.mkdirs()
        val tempFile = File(
            parent ?: File("."),
            "${target.name}.tmp.${atomicFileWriteIdGenerator.incrementAndGet()}.${System.nanoTime()}"
        )
        try {
            FileOutputStream(tempFile).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveFileAtomically(tempFile, target)
        } catch (error: Exception) {
            runCatching {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
            throw error
        }
    }

    private fun moveFileAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}
