@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package moe.ouom.neriplayer.core.player.cache

import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.model.storage.ExtraCacheClearResult

private val imageCacheClearMutex = Mutex()

suspend fun clearRetainingImageCache(
    imageLoader: ImageLoader,
    retainedCoverUrls: Set<String>,
    preserveUnmapped: Boolean = retainedCoverUrls.isNotEmpty()
): ExtraCacheClearResult = withContext(Dispatchers.IO) {
    imageCacheClearMutex.withLock {
        clearImageCacheLocked(imageLoader, retainedCoverUrls, preserveUnmapped)
    }
}

private suspend fun clearImageCacheLocked(
    imageLoader: ImageLoader,
    retainedCoverUrls: Set<String>,
    preserveUnmapped: Boolean
): ExtraCacheClearResult {
    val disk = imageLoader.diskCache
    val before = try {
        disk?.physicalState() ?: ImageCachePhysicalState()
    } catch (_: Exception) {
        return imageClearResult(success = false)
    }
    currentCoroutineContext().ensureActive()
    var success = if (!preserveUnmapped) {
        try {
            disk?.clear()
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    } else if (disk is RetainingImageDiskCache) {
        removeUnretainedImages(disk, retainedCoverUrls)
    } else {
        // 旧缓存的原始键无法枚举，要求保留封面时不能冒险删除未知条目
        true
    }
    try {
        imageLoader.memoryCache?.clear()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        success = false
    }
    val after = try {
        disk?.physicalState() ?: ImageCachePhysicalState()
    } catch (_: Exception) {
        return imageClearResult(success = false)
    }
    return imageClearResult(
        success = success,
        freedBytes = (before.bytes - after.bytes).coerceAtLeast(0),
        deletedFiles = (before.files - after.files).coerceAtLeast(0)
    )
}

private suspend fun removeUnretainedImages(disk: RetainingImageDiskCache, urls: Set<String>): Boolean {
    var success = true
    try {
        disk.knownKeys().forEach { key ->
            currentCoroutineContext().ensureActive()
            if (urls.any { url -> disk.matchesCover(key, url) }) return@forEach
            try {
                val snapshot = disk.openSnapshot(key)
                if (snapshot == null) {
                    // 编辑中的条目也无法读取，确认可编辑后才能判定旧索引已经失效
                    val editor = disk.openEditor(key)
                    if (editor == null) {
                        success = false
                    } else {
                        editor.abort()
                        disk.remove(key)
                    }
                    return@forEach
                }
                snapshot.close()
                if (!disk.remove(key)) success = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                success = false
            }
        }
    } finally {
        disk.flushKeys()
    }
    return success
}

private fun DiskCache.physicalState(): ImageCachePhysicalState {
    if (!fileSystem.exists(directory)) return ImageCachePhysicalState()
    var bytes = 0L
    var files = 0
    fileSystem.listRecursively(directory).forEach { path ->
        val metadata = fileSystem.metadataOrNull(path) ?: return@forEach
        if (metadata.isRegularFile) {
            bytes += metadata.size ?: 0L
            files++
        }
    }
    return ImageCachePhysicalState(bytes, files)
}

private fun imageClearResult(
    success: Boolean,
    freedBytes: Long = 0,
    deletedFiles: Int = 0
) = ExtraCacheClearResult(success, freedBytes, roomBytesMadeReusable = 0, deletedFiles = deletedFiles)

private data class ImageCachePhysicalState(val bytes: Long = 0, val files: Int = 0)
