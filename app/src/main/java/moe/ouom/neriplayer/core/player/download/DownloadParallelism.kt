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
import java.util.concurrent.atomic.AtomicLong

internal const val DEFAULT_DOWNLOAD_PARALLELISM = 6
internal const val MAX_DOWNLOAD_PARALLELISM = 8
internal const val MIN_DOWNLOAD_DISPATCH_WINDOW = 2
internal const val MAX_DOWNLOAD_DISPATCH_WINDOW = 10
private const val DOWNLOAD_DISPATCH_HEADROOM = 2

internal fun resolveDownloadDispatchWindow(networkParallelism: Int): Int {
    // 只保留少量预取名额，避免大量宿主在等待网络许可时占住资源
    return (networkParallelism.coerceAtLeast(1) + DOWNLOAD_DISPATCH_HEADROOM)
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

/** 在各调度后端之间共享设置，避免每次入队都发生阻塞 */
internal fun currentDownloadParallelism(context: Context): Int {
    return DownloadParallelismCache.current(context)
}

internal data class DownloadParallelismSnapshot(
    val value: Int,
    val revision: Long
)

internal fun currentDownloadParallelismSnapshot(context: Context): DownloadParallelismSnapshot {
    return DownloadParallelismCache.snapshot(context)
}

internal fun publishDownloadParallelism(configuredValue: Int) {
    val normalizedValue = normalizeDownloadParallelism(configuredValue)
    val revision = DownloadParallelismCache.publish(normalizedValue)
    AudioDownloadManager.onConfiguredDownloadParallelismChanged(
        configuredValue = normalizedValue,
        configurationRevision = revision
    )
}

private object DownloadParallelismCache {
    private val value = AtomicInteger(INITIAL_DOWNLOAD_PARALLELISM)
    private val revision = AtomicLong(0L)
    private val bootstrapLoadAttempted = AtomicBoolean(false)
    private val observerStarted = AtomicBoolean(false)
    private val bootstrapLoadLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(context: Context): Int {
        loadBootstrapValue(context)
        observe(context)
        return synchronized(bootstrapLoadLock) {
            normalizeDownloadParallelism(value.get())
        }
    }

    fun snapshot(context: Context): DownloadParallelismSnapshot {
        loadBootstrapValue(context)
        observe(context)
        return synchronized(bootstrapLoadLock) {
            DownloadParallelismSnapshot(
                value = normalizeDownloadParallelism(value.get()),
                revision = revision.get()
            )
        }
    }

    fun publish(configuredValue: Int): Long {
        synchronized(bootstrapLoadLock) {
            value.set(normalizeDownloadParallelism(configuredValue))
            bootstrapLoadAttempted.set(true)
            return revision.incrementAndGet()
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
            revision.incrementAndGet()
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
                    publishDownloadParallelism(configuredValue)
                }
            }.onFailure {
                observerStarted.set(false)
            }
        }
    }
}
