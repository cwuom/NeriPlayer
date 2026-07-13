package moe.ouom.neriplayer.core.startup.app

import android.app.Application
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import moe.ouom.neriplayer.core.di.AppContainer

internal object AppImageLoaderInitializer {
    fun initialize(app: Application) {
        val imageLoader = ImageLoader.Builder(app)
            .okHttpClient { AppContainer.sharedOkHttpClient }
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(app)
                    .maxSizePercent(0.12)
                    .build()
            }
            .build()
        Coil.setImageLoader(imageLoader)
    }
}
