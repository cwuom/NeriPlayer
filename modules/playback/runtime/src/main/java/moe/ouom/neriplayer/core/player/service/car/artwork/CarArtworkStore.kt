package moe.ouom.neriplayer.core.player.service.car.artwork

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.ouom.neriplayer.core.player.host.PlayerDependencies
import moe.ouom.neriplayer.core.player.service.artwork.AndroidPlaybackCoverSources
import moe.ouom.neriplayer.core.player.service.artwork.PlaybackCoverSourceResolver
import moe.ouom.neriplayer.data.identity.playbackVisualKey
import moe.ouom.neriplayer.data.local.media.displayCoverUrl
import moe.ouom.neriplayer.data.model.SongItem
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val ARTWORK_LOAD_TIMEOUT_MS = 15_000L
private const val JPEG_QUALITY = 85

internal object CarArtworkStore {
    private data class Request(val song: SongItem, val source: String?)

    private val requests = CarArtworkRequests<Request>()
    private val caches = ConcurrentHashMap<String, CarArtworkDiskCache>()

    fun register(song: SongItem, source: String? = initialSource(song)): String {
        val normalizedSource = (source ?: initialSource(song))?.trim()?.takeIf(String::isNotBlank)
        val key = carArtworkKey(song.playbackVisualKey(), normalizedSource)
        requests.register(key, Request(song, normalizedSource))
        return key
    }

    suspend fun publish(context: Context, key: String, bitmap: Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            val data = encode(bitmap) ?: return@withContext false
            cache(context).save(key, data) != null
        }

    suspend fun file(context: Context, key: String): File? = withContext(Dispatchers.IO) {
        cache(context).find(key)?.let { return@withContext it }
        val request = requests.get(key) ?: return@withContext null
        try {
            withTimeoutOrNull(ARTWORK_LOAD_TIMEOUT_MS) { load(context, key, request) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun load(context: Context, key: String, request: Request): File? {
        if (!PlayerDependencies.isInitialized()) return null
        val resolver = PlaybackCoverSourceResolver(AndroidPlaybackCoverSources(context))
        val source = resolver.immediate(request.song) ?: request.source
            ?: resolver.resolve(request.song, failedSource = null) ?: return null
        val bitmap = loadBitmap(context, source) ?: run {
            val fallback = resolver.resolve(request.song, failedSource = source) ?: return null
            loadBitmap(context, fallback) ?: return null
        }
        return try {
            val data = encode(bitmap) ?: return null
            cache(context).save(key, data)
        } finally {
            bitmap.recycle()
        }
    }

    private suspend fun loadBitmap(context: Context, source: String): Bitmap? = try {
        PlayerDependencies.presentation.loadArtwork(context, source, CAR_ARTWORK_SIZE_PX)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private fun initialSource(song: SongItem): String? =
        song.displayCoverUrl()?.trim()?.takeIf(String::isNotBlank)

    private fun cache(context: Context): CarArtworkDiskCache {
        val directory = File(context.cacheDir, "car-artwork")
        return caches.getOrPut(directory.absolutePath) { CarArtworkDiskCache(directory) }
    }

    private fun encode(bitmap: Bitmap): ByteArray? {
        if (bitmap.isRecycled) return null
        val size = carArtworkSize(bitmap.width, bitmap.height) ?: return null
        val scaled = if (size == (bitmap.width to bitmap.height)) bitmap
        else bitmap.scale(size.first, size.second)
        return try {
            ByteArrayOutputStream().use { output ->
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) null
                else output.toByteArray().takeIf { it.size <= CAR_ARTWORK_MAX_ENTRY_BYTES }
            }
        } finally {
            if (scaled !== bitmap) scaled.recycle()
        }
    }

    suspend fun safelyPublish(context: Context, key: String, bitmap: Bitmap): Boolean = try {
        publish(context, key, bitmap)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        false
    }
}
