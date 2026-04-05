package moe.ouom.neriplayer.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.LruCache
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class CoverArtColorSample(
    val seedHex: String,
    val baseColorArgb: Int
)

object CoverArtColorCache {
    private const val CACHE_SIZE = 64
    private const val COVER_ART_COLOR_SAMPLE_SIZE_PX = 96
    private val cache = LruCache<String, CoverArtColorSample>(CACHE_SIZE)
    private val cacheLock = Any()

    fun peek(coverUrl: String?): CoverArtColorSample? {
        if (coverUrl.isNullOrBlank()) return null
        return synchronized(cacheLock) {
            cache.get(coverUrl)
        }
    }

    suspend fun preload(context: Context, coverUrl: String?): CoverArtColorSample? {
        val normalized = coverUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return getOrLoad(context, normalized)
    }

    suspend fun getOrLoad(context: Context, coverUrl: String): CoverArtColorSample? {
        peek(coverUrl)?.let { return it }

        val loader = Coil.imageLoader(context)
        val request = ImageRequest.Builder(context)
            .data(coverUrl)
            .allowHardware(false)
            .size(COVER_ART_COLOR_SAMPLE_SIZE_PX)
            .precision(Precision.INEXACT)
            .build()
        val result = withContext(Dispatchers.IO) { loader.execute(request) }
        val bitmap = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
        val sample = withContext(Dispatchers.Default) { extract(bitmap) }
        synchronized(cacheLock) {
            cache.put(coverUrl, sample)
        }
        return sample
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
