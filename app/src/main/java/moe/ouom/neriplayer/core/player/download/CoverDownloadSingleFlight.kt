package moe.ouom.neriplayer.core.player.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred

/**
 * 同一封面只允许一个网络请求，其他调用者复用结果或在失败后重新竞选
 *
 * 取消的 owner 不会把取消状态传播给后继调用者，避免批量取消后新下载永久等待旧请求
 */
internal class CoverDownloadSingleFlight<K : Any, V> {
    private val flights = ConcurrentHashMap<K, CompletableDeferred<Outcome<V>>>()

    internal val inFlightCount: Int
        get() = flights.size

    suspend fun run(key: K, block: suspend () -> V): V {
        while (true) {
            val created = CompletableDeferred<Outcome<V>>()
            val active = flights.putIfAbsent(key, created)
            if (active == null) {
                return try {
                    val value = block()
                    created.complete(Outcome.Completed(value))
                    flights.remove(key, created)
                    value
                } catch (cancellation: java.util.concurrent.CancellationException) {
                    flights.remove(key, created)
                    created.complete(Outcome.Retry)
                    throw cancellation
                } catch (error: Throwable) {
                    created.complete(Outcome.Failed(error))
                    flights.remove(key, created)
                    throw error
                }
            }
            when (val outcome = active.await()) {
                is Outcome.Completed -> return outcome.value
                is Outcome.Failed -> throw outcome.error
                Outcome.Retry -> Unit
            }
        }
    }

    private sealed interface Outcome<out V> {
        data class Completed<V>(val value: V) : Outcome<V>

        data class Failed(val error: Throwable) : Outcome<Nothing>

        data object Retry : Outcome<Nothing>
    }
}
