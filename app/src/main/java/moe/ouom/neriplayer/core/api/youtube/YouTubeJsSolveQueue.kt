package moe.ouom.neriplayer.core.api.youtube

import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 让最新到达的求解请求先拿到锁
 *
 * EJS 求解全局串行，队列窗口一铺开就有十几次预取求解排在前面，
 * 用户这时点播放只能等整队跑完；预取是投机的，晚一点无所谓，
 * 而点击必须马上响应，所以按后进先出发牌
 */
internal class YouTubeJsSolveQueue {

    private val monitor = Any()
    private var nextTicket = 0L
    private var busyTicket: Long? = null
    private val waiting = ArrayDeque<Waiter>()
    private val heldTicket = ThreadLocal<Long?>()

    private data class Waiter(
        val ticket: Long,
        val continuation: CancellableContinuation<Long>
    )

    fun <T> withNewestFirst(block: () -> T): T {
        heldTicket.get()?.let {
            // 可挂起入口在可中断区内调用同步求解, 这里复用已经取得的票据
            return block()
        }
        return runBlocking(Dispatchers.IO) {
            withNewestFirstCancellable(block)
        }
    }

    suspend fun <T> withNewestFirstCancellable(block: () -> T): T {
        val ticket = acquireCancellable()
        try {
            return runInterruptible(Dispatchers.IO) {
                val previousTicket = heldTicket.get()
                heldTicket.set(ticket)
                try {
                    block()
                } finally {
                    if (previousTicket == null) {
                        heldTicket.remove()
                    } else {
                        heldTicket.set(previousTicket)
                    }
                }
            }
        } finally {
            release(ticket)
        }
    }

    private suspend fun acquireCancellable(): Long {
        return suspendCancellableCoroutine { continuation ->
            val promotion: Waiter?
            synchronized(monitor) {
                val ticket = nextTicket++
                waiting.addLast(Waiter(ticket, continuation))
                continuation.invokeOnCancellation {
                    cancel(ticket)
                }
                promotion = promoteLocked()
            }
            promotion?.let(::resumeOwner)
        }
    }

    private fun cancel(ticket: Long) {
        val promotion: Waiter?
        synchronized(monitor) {
            val removed = waiting.removeIf { it.ticket == ticket }
            promotion = if (removed) {
                promoteLocked()
            } else {
                null
            }
        }
        promotion?.let(::resumeOwner)
    }

    private fun promoteLocked(): Waiter? {
        if (busyTicket != null) return null
        while (waiting.isNotEmpty()) {
            val waiter = waiting.removeLast()
            if (!waiter.continuation.isActive) continue
            busyTicket = waiter.ticket
            return waiter
        }
        return null
    }

    private fun resumeOwner(waiter: Waiter) {
        waiter.continuation.resume(waiter.ticket) { _, _, _ ->
            release(waiter.ticket)
        }
    }

    private fun release(ticket: Long) {
        val promotion: Waiter?
        synchronized(monitor) {
            if (busyTicket != ticket) return
            busyTicket = null
            promotion = promoteLocked()
        }
        promotion?.let(::resumeOwner)
    }

    internal fun waitingCountForTest(): Int = synchronized(monitor) { waiting.size }
}
