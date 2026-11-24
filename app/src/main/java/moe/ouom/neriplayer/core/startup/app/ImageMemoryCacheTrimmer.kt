package moe.ouom.neriplayer.core.startup.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import coil.memory.MemoryCache

internal const val HIDDEN_UI_IMAGE_CACHE_DIVISOR = 4

// 旋转和应用内跳转会短暂停掉全部 Activity, 等一会儿再裁剪, 避免回来时整页重新解码
internal const val HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS = 10_000L

internal fun hiddenUiImageCacheTargetBytes(level: Int, maxSizeBytes: Int): Int? {
    if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || maxSizeBytes <= 0) return null
    return maxSizeBytes / HIDDEN_UI_IMAGE_CACHE_DIVISOR
}

// keys 先列强引用缓存且按最近最少使用排序, 从头删能保住当前封面
internal fun MemoryCache.trimToBytes(targetBytes: Int): Int {
    if (size <= targetBytes) return 0
    var removed = 0
    for (key in keys.toList()) {
        if (size <= targetBytes) break
        if (remove(key)) removed++
    }
    return removed
}

/**
 * Coil 2 在 UI_HIDDEN 不裁剪强引用缓存; 后台播放时前台服务让进程停在 FOREGROUND_SERVICE,
 * 系统连 UI_HIDDEN 都不会下发, 所以还要按 Activity 是否全部不可见自己判断
 */
internal class ImageMemoryCacheTrimmer(
    private val memoryCache: () -> MemoryCache?,
    private val handler: Handler = Handler(Looper.getMainLooper())
) : ComponentCallbacks2, Application.ActivityLifecycleCallbacks {
    private var startedActivities = 0
    private val trimForHiddenUi = Runnable {
        trim(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
    }

    fun register(app: Application) {
        app.registerComponentCallbacks(this)
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
        handler.removeCallbacks(trimForHiddenUi)
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) {
            handler.removeCallbacks(trimForHiddenUi)
            handler.postDelayed(trimForHiddenUi, HIDDEN_UI_IMAGE_CACHE_TRIM_DELAY_MS)
        }
    }

    override fun onTrimMemory(level: Int) {
        trim(level)
    }

    private fun trim(level: Int) {
        val cache = memoryCache() ?: return
        val target = hiddenUiImageCacheTargetBytes(level, cache.maxSize) ?: return
        cache.trimToBytes(target)
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("kept for ComponentCallbacks compatibility")
    override fun onLowMemory() = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
