@file:OptIn(coil.annotation.ExperimentalCoilApi::class)

package moe.ouom.neriplayer.core.player.cache

import coil.ImageLoader
import coil.disk.DiskCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun estimateRetainingImageCache(
    imageLoader: ImageLoader,
    retainedCoverUrls: Set<String>,
    preserveUnmapped: Boolean
): Long? = withContext(Dispatchers.IO) {
    try {
        currentCoroutineContext().ensureActive()
        val disk = imageLoader.diskCache ?: return@withContext null
        if (!preserveUnmapped) return@withContext disk.size
        // 无法归属的旧缓存会保留，只累计已确认可清理的图片条目
        if (disk !is RetainingImageDiskCache) return@withContext 0L
        var bytes = 0L
        for (key in disk.knownKeys()) {
            currentCoroutineContext().ensureActive()
            if (retainedCoverUrls.any { url -> disk.matchesCover(key, url) }) continue
            disk.snapshotForEstimate(key)?.use { snapshot ->
                bytes += disk.snapshotBytes(snapshot)
            }
        }
        bytes
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}

private suspend fun DiskCache.snapshotBytes(snapshot: DiskCache.Snapshot): Long {
    var bytes = 0L
    for (path in listOf(snapshot.metadata, snapshot.data)) {
        currentCoroutineContext().ensureActive()
        bytes += requireNotNull(fileSystem.metadata(path).size)
    }
    return bytes
}
