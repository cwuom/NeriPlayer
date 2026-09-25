package moe.ouom.neriplayer.core.download.observability

import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicReference

/**
 * 一次共享泵候选选择的计数快照，用于区分查询慢和过滤慢
 */
internal data class DownloadPumpSelectionMetrics(
    val capacity: Int,
    val pagesRead: Int,
    val rowsRead: Int,
    val rowsFilteredAttempted: Int,
    val rowsFilteredDuplicateOperation: Int,
    val rowsFilteredStableKey: Int,
    val rowsDeferredUidt: Int,
    val candidateCount: Int,
    val roomQueryNs: Long,
    val selectionNs: Long
) {
    val roomQueryMs: Double
        get() = roomQueryNs / NANOS_PER_MILLISECOND

    val selectionMs: Double
        get() = selectionNs / NANOS_PER_MILLISECOND

    private companion object {
        private const val NANOS_PER_MILLISECOND = 1_000_000.0
    }
}

/** 采样窗口固定上限，诊断回调失败不能影响下载泵 */
internal class DownloadPumpSelectionMetricsCollector(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
    private val onSample: (DownloadPumpSelectionMetrics) -> Unit = {}
) {
    init {
        require(maxSamples > 0) { "maxSamples must be positive" }
    }

    private val lock = Any()
    private val samples = ArrayDeque<DownloadPumpSelectionMetrics>()

    fun record(sample: DownloadPumpSelectionMetrics) {
        synchronized(lock) {
            if (samples.size >= maxSamples) samples.removeFirst()
            samples.addLast(sample)
        }
        runCatching { onSample(sample) }
    }

    fun snapshot(): List<DownloadPumpSelectionMetrics> = synchronized(lock) {
        samples.toList()
    }

    fun clear() = synchronized(lock) { samples.clear() }

    private companion object {
        private const val DEFAULT_MAX_SAMPLES = 256
    }
}

/** 共享泵只写入进程内有界窗口，不成为调度状态 owner */
internal object DownloadPumpSelectionTrace {
    private val observer = AtomicReference<(DownloadPumpSelectionMetrics) -> Unit>({})
    private val collector = DownloadPumpSelectionMetricsCollector(
        onSample = { sample ->
            runCatching { observer.get().invoke(sample) }
        }
    )

    fun record(sample: DownloadPumpSelectionMetrics) {
        collector.record(sample)
    }

    fun installObserver(nextObserver: (DownloadPumpSelectionMetrics) -> Unit) {
        observer.set(nextObserver)
    }

    internal fun snapshot(): List<DownloadPumpSelectionMetrics> = collector.snapshot()

    internal fun clearForTests() {
        collector.clear()
        observer.set({})
    }
}
