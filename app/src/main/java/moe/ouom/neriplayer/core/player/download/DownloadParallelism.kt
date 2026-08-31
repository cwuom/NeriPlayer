package moe.ouom.neriplayer.core.player.download

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import moe.ouom.neriplayer.data.settings.AutoSettingsSchema
import moe.ouom.neriplayer.data.settings.autoSettingFlow
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val DEFAULT_DOWNLOAD_PARALLELISM = 6
internal const val MAX_DOWNLOAD_PARALLELISM = 8

internal fun normalizeDownloadParallelism(value: Int): Int {
    return value.coerceIn(1, MAX_DOWNLOAD_PARALLELISM)
}

internal suspend fun resolveDownloadParallelism(context: Context): Int {
    val setting = AutoSettingsSchema.download.downloadParallelism
    val configuredValue = runCatching {
        context.applicationContext.autoSettingFlow(setting).first()
    }.getOrDefault(setting.defaultValue)
    return normalizeDownloadParallelism(configuredValue).also(
        DownloadParallelismCache::publish
    )
}

internal fun resolveDownloadParallelismBlocking(context: Context): Int {
    return runCatching {
        runBlocking(Dispatchers.IO) {
            resolveDownloadParallelism(context)
        }
    }.getOrDefault(DEFAULT_DOWNLOAD_PARALLELISM)
}

/** shares the setting across scheduling backends without blocking every enqueue */
internal fun currentDownloadParallelism(context: Context): Int {
    return DownloadParallelismCache.current(context)
}

private object DownloadParallelismCache {
    private val value = AtomicInteger(DEFAULT_DOWNLOAD_PARALLELISM)
    private val initialized = AtomicBoolean(false)
    private val observerStarted = AtomicBoolean(false)
    private val initializationLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun current(context: Context): Int {
        if (!initialized.get()) {
            synchronized(initializationLock) {
                if (!initialized.get()) {
                    value.set(resolveDownloadParallelismBlocking(context))
                    initialized.set(true)
                }
            }
        }
        observe(context)
        return normalizeDownloadParallelism(value.get())
    }

    fun publish(configuredValue: Int) {
        value.set(normalizeDownloadParallelism(configuredValue))
        initialized.set(true)
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
