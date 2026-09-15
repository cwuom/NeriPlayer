package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

internal class BatchDownloadCompletionDispatcher(
    private val scope: CoroutineScope,
    maxConcurrentCallbacks: Int
) {
    private val callbackChannel = Channel<suspend () -> Unit>(CALLBACK_QUEUE_CAPACITY)
    private val callbackWorkerCount = maxConcurrentCallbacks.coerceAtLeast(1)
    private val callbackJobs = mutableListOf<Job>()
    private val callbackFailures = mutableListOf<Throwable>()
    private var workersStarted = false
    private var closed = false

    /** 有界发送让大批量收尾自然背压，不再为每首歌创建一个常驻协程和 Job */
    suspend fun dispatch(callback: suspend () -> Unit) {
        startWorkersIfNeeded()
        callbackChannel.send(callback)
    }

    suspend fun awaitAll() {
        startWorkersIfNeeded()
        synchronized(this) {
            if (!closed) {
                closed = true
                callbackChannel.close()
            }
        }
        val jobs = synchronized(this) { callbackJobs.toList() }
        jobs.joinAll()
        val failure = synchronized(this) { callbackFailures.firstOrNull() }
        if (failure != null) throw failure
    }

    private fun startWorkersIfNeeded() {
        synchronized(this) {
            if (workersStarted) return
            check(!closed) { "completion dispatcher is already closed" }
            workersStarted = true
            repeat(callbackWorkerCount) {
                callbackJobs += scope.launch {
                    for (callback in callbackChannel) {
                        try {
                            callback()
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (error: Throwable) {
                            // 单个可选回调失败不能让其他歌曲的收尾任务消失
                            synchronized(this@BatchDownloadCompletionDispatcher) {
                                callbackFailures += error
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        private const val CALLBACK_QUEUE_CAPACITY = 32
    }
}
