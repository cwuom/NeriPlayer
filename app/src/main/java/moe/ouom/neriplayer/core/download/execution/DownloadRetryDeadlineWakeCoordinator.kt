package moe.ouom.neriplayer.core.download.execution

import android.content.Context

/**
 * 把 Room 中最早的 retry deadline 折叠为一条延迟共享泵请求
 *
 * durable queue 仍是唯一事实来源。该对象只避免没有 ready row 时丢失到期唤醒，
 * 不保留 operation 或创建常驻轮询任务
 */
class DownloadRetryDeadlineWakeCoordinator(
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val schedulePump: (Context, Long) -> Boolean
) {
    enum class ScheduleResult {
        SCHEDULED,
        ALREADY_SCHEDULED,
        FAILED
    }

    private val lock = Any()
    private var scheduledDeadlineMs: Long? = null

    /** 每次泵开始都清除瞬态预约，避免平台提前运行时阻塞重新安排 */
    fun onPumpStarted() = synchronized(lock) {
        scheduledDeadlineMs = null
    }

    /** 只保留最早 deadline；失败不写预约，下次 pump 可继续恢复 */
    fun schedule(context: Context, deadlineMs: Long): ScheduleResult = synchronized(lock) {
        val existing = scheduledDeadlineMs
        if (existing != null && existing <= deadlineMs) {
            return@synchronized ScheduleResult.ALREADY_SCHEDULED
        }
        val delayMs = (deadlineMs - nowMs()).coerceAtLeast(0L)
        if (!schedulePump(context.applicationContext, delayMs)) {
            return@synchronized ScheduleResult.FAILED
        }
        scheduledDeadlineMs = deadlineMs
        ScheduleResult.SCHEDULED
    }

    internal fun scheduledDeadlineForTests(): Long? = synchronized(lock) {
        scheduledDeadlineMs
    }
}
