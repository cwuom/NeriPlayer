package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary

internal const val MAX_RETAINED_CACHE_SONGS = 1000
internal const val RETAINED_CACHE_SONG_STEP = 50
private const val ESTIMATE_DEBOUNCE_MS = 180L

internal fun snapRetainedCacheSongs(value: Float): Int =
    (value.coerceIn(0f, MAX_RETAINED_CACHE_SONGS.toFloat()) / RETAINED_CACHE_SONG_STEP)
        .roundToInt() * RETAINED_CACHE_SONG_STEP

internal data class StorageCacheEstimateRequest(
    val retainedSongs: Int,
    val storageDetails: StorageUsageSummary
)

internal class StorageCacheEstimateState {
    private var generation = 0L
    private var request by mutableStateOf<StorageCacheEstimateRequest?>(null)
    private var estimate by mutableStateOf<PlayerCacheClearEstimate?>(null)
    private var loading by mutableStateOf(false)

    fun estimateFor(expected: StorageCacheEstimateRequest): PlayerCacheClearEstimate? =
        estimate.takeIf { request == expected && !loading }

    fun isPending(expected: StorageCacheEstimateRequest): Boolean = request != expected || loading

    fun reset() {
        generation++
        request = null
        estimate = null
        loading = false
    }

    suspend fun update(
        next: StorageCacheEstimateRequest,
        load: suspend (Int) -> PlayerCacheClearEstimate
    ) {
        val ownGeneration = ++generation
        request = next
        estimate = null
        loading = true
        try {
            delay(ESTIMATE_DEBOUNCE_MS)
            val result = load(next.retainedSongs)
            currentCoroutineContext().ensureActive()
            if (generation == ownGeneration) estimate = result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 无法估算时保留未知状态，不能回退到未扣除保留项的总占用
        } finally {
            if (generation == ownGeneration) loading = false
        }
    }
}

internal fun estimatedCacheBytes(
    kind: StorageCacheKind,
    storage: StorageUsageSummary,
    estimate: PlayerCacheClearEstimate?
): Long? = when (kind) {
    StorageCacheKind.Audio -> estimate?.audioBytes
    StorageCacheKind.Image -> estimate?.imageBytes
    else -> storage.sizeOf(kind)
}

internal fun Iterable<Long?>.sumKnownCacheBytes(): Long? {
    var total = 0L
    for (bytes in this) total += bytes ?: return null
    return total
}
