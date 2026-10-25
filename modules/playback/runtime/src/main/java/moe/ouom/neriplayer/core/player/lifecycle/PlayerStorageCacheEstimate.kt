@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package moe.ouom.neriplayer.core.player.lifecycle

import coil.Coil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.player.PlayerManager
import moe.ouom.neriplayer.core.player.cache.estimateRetainingImageCache
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate

internal suspend fun PlayerManager.estimateStorageCacheClearImpl(
    retainRecentSongs: Int
): PlayerCacheClearEstimate = withContext(Dispatchers.IO) {
    var audioBytes: Long? = null
    try {
        currentCoroutineContext().ensureActive()
        val recent = loadRetainedStorageCacheSongs(retainRecentSongs)
            ?: return@withContext PlayerCacheClearEstimate(null, null)
        val plan = snapshotStorageCacheRetention(recent, retainRecentSongs)
        val audioCache = cache
        audioBytes = if (audioCache == null) null else {
            estimateAudioStorageCache(audioCache, plan.retainedSongs, plan.protectedKeys, plan.preserveUnmapped)
        }
        val imageBytes = estimateRetainingImageCache(
            Coil.imageLoader(application), plan.retainedCoverUrls, plan.preserveUnmapped
        )
        PlayerCacheClearEstimate(audioBytes, imageBytes)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        PlayerCacheClearEstimate(audioBytes, null)
    }
}
