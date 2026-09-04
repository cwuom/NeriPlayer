package moe.ouom.neriplayer.core.player.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import moe.ouom.neriplayer.data.settings.readBootstrapDownloadParallelism
import moe.ouom.neriplayer.data.settings.warmBootstrapSettingsSnapshot
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val DEFAULT_DOWNLOAD_PARALLELISM = 6
internal const val MAX_DOWNLOAD_PARALLELISM = 8
internal const val MIN_DOWNLOAD_DISPATCH_WINDOW = 8
internal const val MAX_DOWNLOAD_DISPATCH_WINDOW = 24

internal fun resolveDownloadDispatchWindow(networkParallelism: Int): Int {
    return (networkParallelism.coerceAtLeast(1) * 2)
        .coerceIn(MIN_DOWNLOAD_DISPATCH_WINDOW, MAX_DOWNLOAD_DISPATCH_WINDOW)
}

internal fun normalizeDownloadParallelism(value: Int): Int {
    return value.coerceIn(1, MAX_DOWNLOAD_PARALLELISM)
}

// 缺少 bootstrap 快照时直接使用产品默认值，避免首批任务被单槽位锁死
internal const val INITIAL_DOWNLOAD_PARALLELISM = DEFAULT_DOWNLOAD_PARALLELISM

internal fun resolveInitialDownloadParallelism(
    persistedValue: Int?
): Int {
    return persistedValue?.let(::normalizeDownloadParallelism)
        ?: INITIAL_DOWNLOAD_PARALLELISM
}

/** shares the setting across scheduling backends without blocking every enqueue */
internal fun currentDownloadParallelism(context: Context): Int {
    return DownloadParallelismCache.current(context)
}

internal fun publishDownloadParallelism(configuredValue: Int) {
    DownloadParallelismCache.publish(configuredValue)
}

private object DownloadParallelismCache {
    private val value = AtomicInteger(INITIAL_DOWNLOAD_PARALLELISM)
    private val bootstrapLoadAttempted = AtomicBoolean(false)
    private val observerStarted = AtomicBoolean(false)
    private val bootstrapLoadLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(context: Context): Int {
        loadBootstrapValue(context)
        observe(context)
        return normalizeDownloadParallelism(value.get())
    }

    fun publish(configuredValue: Int) {
        synchronized(bootstrapLoadLock) {
            value.set(normalizeDownloadParallelism(configuredValue))
            bootstrapLoadAttempted.set(true)
        }
    }

    private fun loadBootstrapValue(context: Context) {
        if (bootstrapLoadAttempted.get()) return
        synchronized(bootstrapLoadLock) {
            if (!bootstrapLoadAttempted.compareAndSet(false, true)) return
            val persistedValue = readBootstrapDownloadParallelism(
                context.applicationContext
            )
            value.set(resolveInitialDownloadParallelism(persistedValue))
            if (persistedValue == null) {
                warmBootstrapSettingsSnapshot(context.applicationContext)
            }
        }
    }

    private fun observe(context: Context) {
        if (!observerStarted.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val setting = AutoSettingsSchema.download.downloadParallelism
        scope.launch {
            runCatching {
                appContext.autoSettingFlow(setting).collect { configuredValue ->
                    publish(configuredValue)
                }
            }.onFailure {
                observerStarted.set(false)
            }
        }
    }
}
