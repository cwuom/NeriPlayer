package moe.ouom.neriplayer.ui.screen.tab.settings.component.storage

import android.graphics.Bitmap
import androidx.test.platform.app.InstrumentationRegistry
import moe.ouom.neriplayer.data.model.storage.PlayerCacheClearEstimate
import moe.ouom.neriplayer.data.model.storage.StorageCacheKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageItem
import moe.ouom.neriplayer.data.model.storage.StorageUsageItemKind
import moe.ouom.neriplayer.data.model.storage.StorageUsageSection
import moe.ouom.neriplayer.data.model.storage.StorageUsageSummary

internal const val CACHE_PREVIEW_MIB = 1024L * 1024L

internal fun cacheCleanupPreviewEstimate(count: Int) = PlayerCacheClearEstimate(
    audioBytes = 64L * CACHE_PREVIEW_MIB - count * 60L * CACHE_PREVIEW_MIB / 1000L,
    imageBytes = 8L * CACHE_PREVIEW_MIB - count * 7L * CACHE_PREVIEW_MIB / 1000L
)

internal fun cacheCleanupPreviewSummary() = StorageUsageSummary(
    listOf(StorageUsageSection("缓存", listOf(
        StorageUsageItem(
            "音频", "", null, 64L * CACHE_PREVIEW_MIB, 18,
            StorageUsageItemKind.AudioCache, cacheKind = StorageCacheKind.Audio
        ),
        StorageUsageItem(
            "图片", "", null, 8L * CACHE_PREVIEW_MIB, 24,
            StorageUsageItemKind.ImageCache, cacheKind = StorageCacheKind.Image
        )
    )))
)

internal fun captureCacheCleanupScreenshot(fileName: String) {
    if (InstrumentationRegistry.getArguments().getString("captureCacheCleanup") != "true") return
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
    try {
        instrumentation.targetContext.cacheDir.resolve(fileName).outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
    } finally {
        bitmap.recycle()
    }
}
