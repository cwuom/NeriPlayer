package moe.ouom.neriplayer.util.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.SuccessResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CoverArtColorSample(
    val seedHex: String,
    val baseColorArgb: Int
)

object CoverArtColorCache {
    private const val CACHE_SIZE = 64
    private const val COVER_ART_COLOR_SAMPLE_SIZE_PX = 96
    private val cache = LruCache<String, CoverArtColorSample>(CACHE_SIZE)
    private val cacheLock = Any()
    private val inFlightLoads = mutableMapOf<String, CompletableDeferred<CoverArtColorSample?>>()

    fun peek(coverUrl: String?): CoverArtColorSample? {
        val cacheKey = normalizeCoverArtColorCacheKey(coverUrl) ?: return null
        return synchronized(cacheLock) {
            cache.get(cacheKey)
        }
    }

    suspend fun preload(
        context: Context,
        coverUrl: String?,
        offlineMode: Boolean = context.isOfflineModeNow()
    ): CoverArtColorSample? {
        val normalized = coverUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return getOrLoad(context, normalized, offlineMode)
    }

    suspend fun getOrLoad(
        context: Context,
        coverUrl: String,
        offlineMode: Boolean = context.isOfflineModeNow()
    ): CoverArtColorSample? {
        val cacheKey = normalizeCoverArtColorCacheKey(coverUrl) ?: return null
        peek(coverUrl)?.let { return it }

        var pendingLoad: CompletableDeferred<CoverArtColorSample?>? = null
        var ownsLoad = false
        synchronized(cacheLock) {
            val cached = cache.get(cacheKey)
            if (cached != null) {
                return cached
            }
            pendingLoad = inFlightLoads[cacheKey]
            if (pendingLoad == null) {
                val createdLoad = CompletableDeferred<CoverArtColorSample?>()
                pendingLoad = createdLoad
                inFlightLoads[cacheKey] = createdLoad
                ownsLoad = true
            }
        }
        val deferred = pendingLoad ?: return null
        if (!ownsLoad) {
            return deferred.await()
        }

        val sample = runCatching {
            loadSample(context, normalizeCoverArtRequestUrl(coverUrl), offlineMode)
        }.getOrNull()

        synchronized(cacheLock) {
            if (sample != null) {
                cache.put(cacheKey, sample)
            }
            inFlightLoads.remove(cacheKey)
        }
        deferred.complete(sample)
        return sample
    }

    private suspend fun loadSample(
        context: Context,
        coverUrl: String,
        offlineMode: Boolean
    ): CoverArtColorSample? {
        val loader = Coil.imageLoader(context)
        val request = offlineCachedImageRequest(
            context = context,
            data = coverUrl,
            sizePx = COVER_ART_COLOR_SAMPLE_SIZE_PX,
            allowHardware = false,
            offlineMode = offlineMode
        )
        val result = withContext(Dispatchers.IO) { loader.execute(request) }
        val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
        return withContext(Dispatchers.Default) { extract(bitmap) }
    }

    private fun extract(bitmap: Bitmap): CoverArtColorSample {
        val palette = Palette.from(bitmap)
            .clearFilters()
            .generate()
        val baseColor = palette.getVibrantColor(
            palette.getMutedColor(
                palette.getDominantColor(0xFF808080.toInt())
            )
        )
        val r = (baseColor shr 16) and 0xFF
        val g = (baseColor shr 8) and 0xFF
        val b = baseColor and 0xFF
        return CoverArtColorSample(
            seedHex = String.format("%02X%02X%02X", r, g, b),
            baseColorArgb = baseColor
        )
    }
}

internal fun normalizeCoverArtColorCacheKey(coverUrl: String?): String? {
    val normalized = coverUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parsed = normalized.toHttpUrlOrNull() ?: return normalized
    if (!parsed.isNeteaseCoverHost()) return normalized
    return "netease-cover:${parsed.encodedPath}"
}

private fun normalizeCoverArtRequestUrl(coverUrl: String): String {
    val normalized = coverUrl.trim()
    val parsed = normalized.toHttpUrlOrNull() ?: return normalized
    if (!parsed.isNeteaseCoverHost() || parsed.scheme == "https") return normalized
    return parsed.newBuilder().scheme("https").build().toString()
}

private fun okhttp3.HttpUrl.isNeteaseCoverHost(): Boolean {
    return host == "music.126.net" || host.endsWith(".music.126.net")
}

fun adjustedAccentColorArgb(baseColorArgb: Int, isDark: Boolean): Int {
    val r = (baseColorArgb shr 16) and 0xFF
    val g = (baseColorArgb shr 8) and 0xFF
    val b = baseColorArgb and 0xFF
    val hsl = FloatArray(3)
    ColorUtils.RGBToHSL(r, g, b, hsl)

    val targetS = if (isDark) {
        (hsl[1] * 0.38f).coerceAtMost(0.30f)
    } else {
        (hsl[1] * 0.32f).coerceAtMost(0.24f)
    }

    val targetL = if (isDark) {
        hsl[2].coerceIn(0.18f, 0.26f)
    } else {
        0.90f
    }

    val outInt = ColorUtils.HSLToColor(floatArrayOf(hsl[0], targetS, targetL))
    val neutralInt = if (isDark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
    return ColorUtils.blendARGB(
        outInt,
        neutralInt,
        if (isDark) 0.22f else 0.28f
    )
}
