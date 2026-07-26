package moe.ouom.neriplayer.core.api.youtube

/**
 * 让最新到达的求解请求先拿到锁
 *
 * EJS 求解全局串行，队列窗口一铺开就有十几次预取求解排在前面，
 * 用户这时点播放只能等整队跑完；预取是投机的，晚一点无所谓，
 * 而点击必须马上响应，所以按后进先出发牌
 */
internal class YouTubeJsSolveQueue {

    private val monitor = Object()
    private var nextTicket = 0L
    private var busy = false
    private val waiting = ArrayDeque<Long>()

    fun <T> withNewestFirst(block: () -> T): T {
        val ticket = acquire()
        try {
            return block()
        } finally {
            release(ticket)
        }
    }

    private fun acquire(): Long {
        synchronized(monitor) {
            val ticket = nextTicket++
            waiting.addLast(ticket)
            while (busy || waiting.maxOrNull() != ticket) {
                monitor.wait()
            }
            waiting.remove(ticket)
            busy = true
            return ticket
        }
    }

    private fun release(ticket: Long) {
        synchronized(monitor) {
            busy = false
            waiting.remove(ticket)
            monitor.notifyAll()
        }
    }

    internal fun waitingCountForTest(): Int = synchronized(monitor) { waiting.size }
}
