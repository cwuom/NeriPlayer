package moe.ouom.neriplayer.core.player.dlna.session

/**
 * 决定是否需要为当前曲目启动一次下一首预取。
 *
 * 轮询每秒都会请求预取；预取任务本身要先等待一段时间再解析 URL，
 * 如果每次请求都取消重来，任务永远跑不到 SetNextAVTransportURI。
 * 这里按 (连接, 曲目, 队列版本) 去重：同一目标的任务在跑或已经跑完就不再重启。
 */
internal class DlnaPrequeueGate {

    data class Target(val connectionGen: Long, val trackGen: Long, val queueRevision: Long)

    private var inFlight: Target? = null
    private var finished: Target? = null

    @Synchronized
    fun tryBegin(target: Target, jobActive: Boolean): Boolean {
        if (target == finished) return false
        if (target == inFlight && jobActive) return false
        inFlight = target
        return true
    }

    /** 仅在任务正常走完时调用；被取消或用户暂停的任务应允许之后重试 */
    @Synchronized
    fun markFinished(target: Target) {
        finished = target
        if (inFlight == target) inFlight = null
    }
}
