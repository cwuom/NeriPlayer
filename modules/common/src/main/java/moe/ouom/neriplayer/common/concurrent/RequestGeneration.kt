package moe.ouom.neriplayer.common.concurrent

/**
 * 每轮请求共享一个票据，推进后旧票据永久失效
 *
 * 票据检查与状态写入不是原子操作，调用方仍需使用所属线程或锁
 */
class RequestGeneration {
    @Volatile
    private var current = Ticket(this)

    fun capture(): Ticket = current

    fun advance(): Ticket {
        val ticket = Ticket(this)
        current = ticket
        return ticket
    }

    class Ticket internal constructor(private val owner: RequestGeneration) {
        val isCurrent: Boolean
            get() = owner.current === this
    }
}
