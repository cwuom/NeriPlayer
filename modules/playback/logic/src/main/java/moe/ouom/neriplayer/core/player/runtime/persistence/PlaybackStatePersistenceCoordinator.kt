package moe.ouom.neriplayer.core.player.runtime.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.ouom.neriplayer.common.concurrent.RequestGeneration
import kotlin.time.Duration.Companion.milliseconds

class PlaybackStatePersistenceCoordinator<Snapshot> {
    class Request<Snapshot> internal constructor(
        internal val owner: PlaybackStatePersistenceCoordinator<Snapshot>,
        internal val ticket: RequestGeneration.Ticket,
        internal val snapshot: Snapshot
    )

    private val lock = Any()
    private val writerMutex = Mutex()
    private var generation: RequestGeneration? = RequestGeneration()
    private var scheduledJob: Job? = null
    private var activeWriter: Job? = null

    fun prepare(capture: () -> Snapshot): Request<Snapshot>? = synchronized(lock) {
        val activeGeneration = generation ?: return@synchronized null
        val ticket = activeGeneration.advance()
        cancelScheduledLocked()
        Request(this, ticket, capture())
    }

    fun schedule(
        scope: CoroutineScope,
        request: Request<Snapshot>,
        debounceMs: Long,
        write: suspend (Snapshot) -> Unit
    ) {
        val job = synchronized(lock) {
            if (!isCurrent(request)) return
            cancelScheduledLocked()
            val next = scope.launch(start = CoroutineStart.LAZY) {
                if (debounceMs > 0L) delay(debounceMs.milliseconds)
                persist(request, write)
            }
            scheduledJob = next
            next.invokeOnCompletion {
                synchronized(lock) {
                    if (scheduledJob === next) scheduledJob = null
                }
            }
            next
        }
        job.start()
    }

    suspend fun persist(request: Request<Snapshot>, write: suspend (Snapshot) -> Unit): Boolean =
        writerMutex.withLock {
            coroutineScope {
                currentCoroutineContext().ensureActive()
                val writer = currentCoroutineContext().job
                synchronized(lock) {
                    if (!isCurrent(request)) return@coroutineScope false
                    activeWriter = writer
                }
                try {
                    write(request.snapshot)
                    isCurrent(request)
                } finally {
                    synchronized(lock) {
                        if (activeWriter === writer) activeWriter = null
                    }
                }
            }
        }

    fun close() = synchronized(lock) {
        generation?.advance()
        generation = null
        cancelScheduledLocked()
        cancelWriterLocked()
    }

    fun reopen() = synchronized(lock) {
        generation?.advance()
        cancelScheduledLocked()
        cancelWriterLocked()
        generation = RequestGeneration()
    }

    private fun isCurrent(request: Request<Snapshot>): Boolean =
        request.owner === this && request.ticket.isCurrent

    private fun cancelScheduledLocked() {
        val previous = scheduledJob
        scheduledJob = null
        previous?.cancel()
    }

    private fun cancelWriterLocked() {
        // 只取消本次写入的子协程，调用方可能还需要完成其它收尾工作
        val previous = activeWriter
        activeWriter = null
        previous?.cancel()
    }
}
