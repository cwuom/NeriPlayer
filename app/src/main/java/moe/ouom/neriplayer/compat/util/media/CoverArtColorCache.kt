package moe.ouom.neriplayer.util

import android.content.Context
import moe.ouom.neriplayer.data.traffic.isOfflineModeNow
import moe.ouom.neriplayer.util.media.adjustedAccentColorArgb as adjustedAccentColorArgbImpl

data class CoverArtColorSample(
    val seedHex: String,
    val baseColorArgb: Int
)

object CoverArtColorCache {
    fun peek(coverUrl: String?): CoverArtColorSample? {
        return moe.ouom.neriplayer.util.media.CoverArtColorCache
            .peek(coverUrl)
            ?.toRootSample()
    }

    suspend fun preload(
        context: Context,
        coverUrl: String?,
        offlineMode: Boolean = context.isOfflineModeNow()
    ): CoverArtColorSample? {
        return moe.ouom.neriplayer.util.media.CoverArtColorCache
            .preload(context, coverUrl, offlineMode)
            ?.toRootSample()
    }

    suspend fun getOrLoad(
        context: Context,
        coverUrl: String,
        offlineMode: Boolean = context.isOfflineModeNow()
    ): CoverArtColorSample? {
        return moe.ouom.neriplayer.util.media.CoverArtColorCache
            .getOrLoad(context, coverUrl, offlineMode)
            ?.toRootSample()
    }
}

fun adjustedAccentColorArgb(baseColorArgb: Int, isDark: Boolean): Int {
    return adjustedAccentColorArgbImpl(baseColorArgb, isDark)
}

private fun moe.ouom.neriplayer.util.media.CoverArtColorSample.toRootSample(): CoverArtColorSample {
    return CoverArtColorSample(
        seedHex = seedHex,
        baseColorArgb = baseColorArgb
    )
}
