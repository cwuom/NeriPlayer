package moe.ouom.neriplayer.core.download.observability

import java.util.concurrent.atomic.AtomicReference
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 记录一次启动恢复从意图到首个真实传输的单调时钟节点
 *
 * 这个类只负责采样，不会把不可执行的网络、Provider 或系统配额状态伪装成超时。
 * 业务状态仍由持久化下载引擎维护
 */
internal class DownloadStartupDeadlineTracker(
    private val nowNs: () -> Long = System::nanoTime,
    private val deadlineNs: Long = DEFAULT_DEADLINE_NS,
    private val onSnapshot: (Snapshot) -> Unit = {}
) {
    init {
        require(deadlineNs >= 0L) { "deadlineNs must not be negative" }
    }

    enum class Phase {
        IDLE,
        INTENT_RECORDED,
        QUEUE_READY,
        TRANSFER_STARTED,
        BLOCKED
    }

    data class Snapshot(
        val generation: Long,
        val phase: Phase,
        val t0Ns: Long?,
        val t1Ns: Long?,
        val t2Ns: Long?,
        val blockedReason: String?,
        val t0ToT1Ns: Long?,
        val t1ToT2Ns: Long?,
        val t0ToT2Ns: Long?,
        val withinDeadline: Boolean?
    )

    private val lock = Any()
    private var generation = 0L
    private var phase = Phase.IDLE
    private var t0Ns: Long? = null
    private var t1Ns: Long? = null
    private var t2Ns: Long? = null
    private var blockedReason: String? = null

    /** 开始新一轮启动采样，旧进程或旧恢复回调不能写入新代次 */
    fun begin(previousGeneration: Long? = null): Snapshot {
        return update {
            val restoredGeneration = previousGeneration?.coerceAtLeast(0L) ?: 0L
            generation = maxOf(generation, restoredGeneration)
            generation = if (generation == Long.MAX_VALUE) 1L else generation + 1L
            phase = Phase.INTENT_RECORDED
            t0Ns = nowNs()
            t1Ns = null
            t2Ns = null
            blockedReason = null
        }
    }

    /** 队列 header 已读取并可交给新的宿主接管 */
    fun markQueueReady(expectedGeneration: Long? = null): Snapshot? {
        return updateIfCurrent(expectedGeneration) {
            if (
                t0Ns == null ||
                phase == Phase.BLOCKED ||
                phase == Phase.TRANSFER_STARTED
            ) {
                return@updateIfCurrent
            }
            if (t1Ns == null) {
                t1Ns = nowNs()
                phase = Phase.QUEUE_READY
            }
        }
    }

    /** 第一个 operation 已开始真实网络 I/O，而不是只有 RUNNING/admission 记录 */
    fun markTransferStarted(expectedGeneration: Long? = null): Snapshot? {
        return updateIfCurrent(expectedGeneration) {
            if (phase == Phase.BLOCKED || t2Ns != null || t0Ns == null) {
                return@updateIfCurrent
            }
            val startedAtNs = nowNs()
            if (t1Ns == null) {
                t1Ns = startedAtNs
            }
            t2Ns = startedAtNs
            phase = Phase.TRANSFER_STARTED
        }
    }

    /** 在不可执行条件下记录原因，保留 T0/T1 现场供后续恢复对账 */
    fun markBlocked(
        reason: String,
        expectedGeneration: Long? = null
    ): Snapshot? {
        val normalizedReason = reason.trim().ifBlank { UNKNOWN_BLOCK_REASON }
        return updateIfCurrent(expectedGeneration) {
            if (t0Ns != null && t2Ns == null && phase != Phase.TRANSFER_STARTED) {
                blockedReason = normalizedReason
                phase = Phase.BLOCKED
            }
        }
    }

    fun snapshot(): Snapshot {
        synchronized(lock) {
            return snapshotLocked()
        }
    }

    private fun update(block: () -> Unit): Snapshot {
        val snapshot = synchronized(lock) {
            block()
            snapshotLocked()
        }
        publish(snapshot)
        return snapshot
    }

    private fun updateIfCurrent(
        expectedGeneration: Long?,
        block: () -> Unit
    ): Snapshot? {
        val snapshot = synchronized(lock) {
            if (expectedGeneration != null && expectedGeneration != generation) {
                return@synchronized null
            }
            block()
            snapshotLocked()
        } ?: return null
        publish(snapshot)
        return snapshot
    }

    private fun snapshotLocked(): Snapshot {
        val firstBoundaryNs = t0Ns
        val queueBoundaryNs = t1Ns
        val transferBoundaryNs = t2Ns
        return Snapshot(
            generation = generation,
            phase = phase,
            t0Ns = firstBoundaryNs,
            t1Ns = queueBoundaryNs,
            t2Ns = transferBoundaryNs,
            blockedReason = blockedReason,
            t0ToT1Ns = elapsedNs(firstBoundaryNs, queueBoundaryNs),
            t1ToT2Ns = elapsedNs(queueBoundaryNs, transferBoundaryNs),
            t0ToT2Ns = elapsedNs(firstBoundaryNs, transferBoundaryNs),
            withinDeadline = elapsedNs(firstBoundaryNs, transferBoundaryNs)
                ?.let { elapsed -> elapsed <= deadlineNs }
        )
    }

    private fun elapsedNs(startNs: Long?, endNs: Long?): Long? {
        if (startNs == null || endNs == null) return null
        return (endNs - startNs).coerceAtLeast(0L)
    }

    private fun publish(snapshot: Snapshot) {
        runCatching { onSnapshot(snapshot) }
            .onFailure { error ->
                NPLogger.d(
                    TAG,
                    "启动恢复诊断回调失败，不影响下载状态: ${error.message}"
                )
            }
    }

    companion object {
        private const val TAG = "DownloadStartupTrace"
        private const val UNKNOWN_BLOCK_REASON = "unspecified"
        private const val DEFAULT_DEADLINE_NS = 5_000_000_000L
    }
}

/** 进程内唯一启动采样入口，避免不同宿主各自创建无法对账的计时器 */
internal object DownloadStartupTrace {
    private val snapshotObserver = AtomicReference<(DownloadStartupDeadlineTracker.Snapshot) -> Unit>({})
    private val tracker = DownloadStartupDeadlineTracker(
        onSnapshot = { snapshot ->
            snapshotObserver.get().invoke(snapshot)
        }
    )

    /** 安装进程级持久诊断观察者，观察者异常由 tracker 隔离 */
    fun installObserver(observer: (DownloadStartupDeadlineTracker.Snapshot) -> Unit) {
        snapshotObserver.set(observer)
    }

    fun begin(previousGeneration: Long? = null): Long {
        return tracker.begin(previousGeneration).generation
    }

    fun markQueueReady() {
        tracker.markQueueReady()
    }

    fun markTransferStarted() {
        tracker.markTransferStarted()
    }

    fun markBlocked(reason: String, generation: Long? = null) {
        tracker.markBlocked(
            reason = reason,
            expectedGeneration = generation
        )
    }

    fun snapshot(): DownloadStartupDeadlineTracker.Snapshot {
        return tracker.snapshot()
    }
}
