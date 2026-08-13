@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package moe.ouom.neriplayer.core.player.prefetch

import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.player.PlayerManager

private const val MEDIA_PREFETCH_BUFFER_BYTES = 64 * 1024
private const val MEDIA_PREFETCH_MIN_BYTES = 256L * 1024L

internal suspend fun PlayerManager.prefetchIntoPlayerCache(
    url: String,
    cacheKey: String,
    targetBytes: Long
): Long = withContext(Dispatchers.IO) {
    val mediaCache = cache ?: return@withContext 0L
    val upstreamFactory = conditionalHttpFactory ?: return@withContext 0L
    val requestedBytes = targetBytes.coerceAtLeast(MEDIA_PREFETCH_MIN_BYTES)
    if (playbackDemandArbiter.shouldYieldPrefetch(cacheKey)) {
        return@withContext 0L
    }
    val cacheDataSource = CacheDataSource.Factory()
        .setCache(mediaCache)
        .setUpstreamDataSourceFactory(upstreamFactory)
        .setFlags(CacheDataSource.FLAG_BLOCK_ON_CACHE)
        .setEventListener(object : CacheDataSource.EventListener {
            override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                AppContainer.trafficStatsRepo.recordCacheHitBytes(cachedBytesRead)
            }

            override fun onCacheIgnored(reason: Int) = Unit
        })
        .createDataSource()
    val dataSpec = DataSpec.Builder()
        .setUri(url.toUri())
        .setKey(cacheKey)
        .setPosition(0L)
        .setLength(requestedBytes)
        .build()
    val buffer = ByteArray(MEDIA_PREFETCH_BUFFER_BYTES)
    var totalRead = 0L
    try {
        cacheDataSource.open(dataSpec)
        while (totalRead < requestedBytes) {
            if (playbackDemandArbiter.shouldYieldPrefetch(cacheKey)) {
                break
            }
            val bytesToRead = minOf(
                buffer.size.toLong(),
                requestedBytes - totalRead
            ).toInt()
            val read = cacheDataSource.read(buffer, 0, bytesToRead)
            if (read == C.RESULT_END_OF_INPUT || read < 0) {
                break
            }
            totalRead += read.toLong()
        }
    } finally {
        runCatching { cacheDataSource.close() }
    }
    totalRead
}
