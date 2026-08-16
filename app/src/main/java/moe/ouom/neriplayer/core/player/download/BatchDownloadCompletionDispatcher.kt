package moe.ouom.neriplayer.core.player.download

import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore

internal class BatchDownloadCompletionDispatcher(
    private val scope: CoroutineScope,
    maxConcurrentCallbacks: Int
) {
    private val callbackSemaphore = Semaphore(maxConcurrentCallbacks.coerceAtLeast(1))
    private val callbackJobs = Collections.synchronizedList(mutableListOf<Job>())

    fun dispatch(callback: suspend () -> Unit) {
        val job = scope.launch {
            callbackSemaphore.acquire()
            try {
                callback()
            } finally {
                callbackSemaphore.release()
            }
        }
        callbackJobs += job
    }

    suspend fun awaitAll() {
        val jobs = synchronized(callbackJobs) { callbackJobs.toList() }
        jobs.joinAll()
    }
}
