package moe.ouom.neriplayer.core.download.observability

import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * 下载 operation 的轻量时序采样器
 *
 * 采样器只保存一个有界的进程内窗口，持久化 operation 的状态仍由 Room 和现有 journal
 * 负责。每个 attempt 都通过 revision 隔离，迟到回调只能丢弃诊断数据，不能覆盖新 attempt
 */
internal enum class DownloadOperationTracePhase {
    ENQUEUED,
    QUEUE_SELECTED,
    HOST_ADMISSION_REQUESTED,
    HOST_ADMISSION_GRANTED,
    BACKEND_SCHEDULED,
    BACKEND_STARTED,
    SOURCE_RESOLVE_STARTED,
    SOURCE_RESOLVE_FINISHED,
    PREPARE_STARTED,
    PREPARE_FINISHED,
    NETWORK_PERMIT_REQUESTED,
    NETWORK_PERMIT_GRANTED,
    NETWORK_STARTED,
    NETWORK_FINISHED,
    NETWORK_PERMIT_RELEASED,
    CORE_COMMIT_REQUESTED,
    CORE_COMMIT_GRANTED,
    CORE_COMMIT_STARTED,
    CORE_COMMIT_FINISHED,
    CORE_COMMITTED,
    ENRICHMENT_ENQUEUED,
    ENRICHMENT_STARTED,
    ENRICHMENT_METADATA_STARTED,
    ENRICHMENT_METADATA_FINISHED,
    ENRICHMENT_COVER_STARTED,
    ENRICHMENT_COVER_FINISHED,
    ENRICHMENT_LYRICS_STARTED,
    ENRICHMENT_LYRICS_FINISHED,
    ENRICHMENT_TAG_STARTED,
    ENRICHMENT_TAG_FINISHED,
    ENRICHMENT_FINISHED,
    TERMINAL
}

internal data class DownloadOperationTiming(
    val operationId: String,
    val attemptId: Long?,
    val revision: Long,
    val marksNs: Map<DownloadOperationTracePhase, Long>
) {
    val queueWaitNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENQUEUED,
            DownloadOperationTracePhase.QUEUE_SELECTED
        )

    val hostAdmissionWaitNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.HOST_ADMISSION_REQUESTED,
            DownloadOperationTracePhase.HOST_ADMISSION_GRANTED
        )

    val backendStartWaitNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.BACKEND_SCHEDULED,
            DownloadOperationTracePhase.BACKEND_STARTED
        )

    val resolveNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.SOURCE_RESOLVE_STARTED,
            DownloadOperationTracePhase.SOURCE_RESOLVE_FINISHED
        )

    val prepareNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.PREPARE_STARTED,
            DownloadOperationTracePhase.PREPARE_FINISHED
        )

    val networkPermitWaitNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.NETWORK_PERMIT_REQUESTED,
            DownloadOperationTracePhase.NETWORK_PERMIT_GRANTED
        )

    val transferNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.NETWORK_STARTED,
            DownloadOperationTracePhase.NETWORK_FINISHED
        )

    val networkPermitHeldNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.NETWORK_PERMIT_GRANTED,
            DownloadOperationTracePhase.NETWORK_PERMIT_RELEASED
        )

    val coreCommitWaitNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.CORE_COMMIT_REQUESTED,
            DownloadOperationTracePhase.CORE_COMMIT_GRANTED
        )

    val coreCommitIoNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.CORE_COMMIT_STARTED,
            DownloadOperationTracePhase.CORE_COMMIT_FINISHED
        )

    val enrichmentQueueWaitNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENRICHMENT_ENQUEUED,
            DownloadOperationTracePhase.ENRICHMENT_STARTED
        )

    val enrichmentNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENRICHMENT_STARTED,
            DownloadOperationTracePhase.ENRICHMENT_FINISHED
        )

    val metadataNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENRICHMENT_METADATA_STARTED,
            DownloadOperationTracePhase.ENRICHMENT_METADATA_FINISHED
        )

    val coverNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENRICHMENT_COVER_STARTED,
            DownloadOperationTracePhase.ENRICHMENT_COVER_FINISHED
        )

    val lyricsNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENRICHMENT_LYRICS_STARTED,
            DownloadOperationTracePhase.ENRICHMENT_LYRICS_FINISHED
        )

    val tagNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENRICHMENT_TAG_STARTED,
            DownloadOperationTracePhase.ENRICHMENT_TAG_FINISHED
        )

    val lifetimeNs: Long?
        get() = elapsed(
            DownloadOperationTracePhase.ENQUEUED,
            DownloadOperationTracePhase.TERMINAL
        )

    fun markNs(phase: DownloadOperationTracePhase): Long? = marksNs[phase]

    private fun elapsed(
        start: DownloadOperationTracePhase,
        end: DownloadOperationTracePhase
    ): Long? {
        val startNs = marksNs[start] ?: return null
        val endNs = marksNs[end] ?: return null
        return (endNs - startNs).coerceAtLeast(0L)
    }
}

/** 只作为诊断句柄，不持有业务状态，也不允许调用方伪造 revision */
internal class DownloadOperationTraceToken internal constructor(
    val operationId: String,
    val attemptId: Long?,
    val revision: Long,
    internal val accepted: Boolean
)

internal class DownloadOperationTimingCollector(
    private val nowNs: () -> Long = System::nanoTime,
    private val maxOperations: Int = DEFAULT_MAX_OPERATIONS,
    private val onSnapshot: (DownloadOperationTiming) -> Unit = {}
) {
    init {
        require(maxOperations > 0) { "maxOperations must be positive" }
    }

    private class Entry(
        val operationId: String,
        val attemptId: Long?,
        val revision: Long,
        val marksNs: LongArray,
        var terminal: Boolean = false,
        var markVersion: Long = 0L,
        var terminalVersion: Long = -1L
    )

    private val stateLock = Any()
    private val entries = LinkedHashMap<String, Entry>()
    private var nextRevision = 0L

    /** 开始或取得同一 operation/attempt 的诊断句柄 */
    fun begin(
        operationId: String,
        attemptId: Long? = null
    ): DownloadOperationTraceToken? {
        val normalizedOperationId = normalizeOperationId(operationId) ?: return null
        return runCatching {
            synchronized(stateLock) {
                val existing = entries[normalizedOperationId]
                when {
                    existing != null && existing.attemptId == attemptId -> tokenFor(existing)
                    existing != null && !isNewerAttempt(attemptId, existing.attemptId) -> null
                    else -> {
                        entries.remove(normalizedOperationId)
                        evictForNewEntryLocked()
                        if (entries.size >= maxOperations) {
                            tokenForDropped(normalizedOperationId, attemptId)
                        } else {
                            val entry = Entry(
                                operationId = normalizedOperationId,
                                attemptId = attemptId,
                                revision = nextRevision(),
                                marksNs = LongArray(DownloadOperationTracePhase.entries.size) {
                                    UNSET_MARK_NS
                                }
                            )
                            entries[normalizedOperationId] = entry
                            tokenFor(entry)
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /** 只接受仍然指向当前 revision 的句柄，重复 phase 不重复发样本 */
    fun mark(
        token: DownloadOperationTraceToken?,
        phase: DownloadOperationTracePhase
    ): DownloadOperationTiming? {
        if (token == null || !token.accepted) return null
        val snapshot = runCatching {
            synchronized(stateLock) {
                val entry = entries[token.operationId]
                    ?.takeIf { current ->
                        current.revision == token.revision &&
                            current.attemptId == token.attemptId
                    }
                    ?: return@synchronized null
                val changed = if (phase == DownloadOperationTracePhase.TERMINAL) {
                    // host 可能先结束而 enrichment 仍在后台运行；有新的阶段后允许
                    // 最终 terminal 刷新一次，重复 terminal 本身仍保持幂等
                    if (entry.terminal && entry.markVersion == entry.terminalVersion) {
                        false
                    } else {
                        entry.marksNs[phase.ordinal] = nowNs()
                        entry.terminal = true
                        entry.markVersion++
                        entry.terminalVersion = entry.markVersion
                        true
                    }
                } else if (entry.marksNs[phase.ordinal] == UNSET_MARK_NS) {
                    entry.marksNs[phase.ordinal] = nowNs()
                    entry.markVersion++
                    true
                } else {
                    false
                }
                if (changed) snapshotLocked(entry) else null
            }
        }.getOrNull()
        publish(snapshot)
        return snapshot
    }

    /** 按身份查找当前句柄，调用方仍必须提供 attemptId */
    fun mark(
        operationId: String,
        attemptId: Long?,
        phase: DownloadOperationTracePhase
    ): DownloadOperationTiming? {
        val normalizedOperationId = normalizeOperationId(operationId) ?: return null
        val token = runCatching {
            synchronized(stateLock) {
                entries[normalizedOperationId]
                    ?.takeIf { entry -> entry.attemptId == attemptId }
                    ?.let(::tokenFor)
            }
        }.getOrNull()
        return mark(token, phase)
    }

    fun snapshot(token: DownloadOperationTraceToken?): DownloadOperationTiming? {
        if (token == null || !token.accepted) return null
        return runCatching {
            synchronized(stateLock) {
                entries[token.operationId]
                    ?.takeIf { entry ->
                        entry.revision == token.revision && entry.attemptId == token.attemptId
                    }
                    ?.let(::snapshotLocked)
            }
        }.getOrNull()
    }

    fun snapshot(
        operationId: String,
        attemptId: Long?
    ): DownloadOperationTiming? {
        val normalizedOperationId = normalizeOperationId(operationId) ?: return null
        return runCatching {
            synchronized(stateLock) {
                entries[normalizedOperationId]
                    ?.takeIf { entry -> entry.attemptId == attemptId }
                    ?.let(::snapshotLocked)
            }
        }.getOrNull()
    }

    fun size(): Int = synchronized(stateLock) { entries.size }

    fun end(token: DownloadOperationTraceToken?): DownloadOperationTiming? {
        return mark(token, DownloadOperationTracePhase.TERMINAL)
    }

    internal fun clearForTests() {
        synchronized(stateLock) {
            entries.clear()
        }
    }

    private fun normalizeOperationId(operationId: String): String? {
        return operationId.trim().takeIf(String::isNotBlank)
    }

    private fun isNewerAttempt(
        requestedAttemptId: Long?,
        currentAttemptId: Long?
    ): Boolean {
        return when {
            currentAttemptId == null && requestedAttemptId != null -> true
            currentAttemptId != null && requestedAttemptId == null -> false
            currentAttemptId != null && requestedAttemptId != null ->
                requestedAttemptId > currentAttemptId

            else -> false
        }
    }

    private fun evictForNewEntryLocked() {
        if (entries.size < maxOperations) return
        val terminalKey = entries.entries.firstOrNull { (_, entry) -> entry.terminal }?.key
        if (terminalKey != null) {
            entries.remove(terminalKey)
            return
        }
        // 采样器不能反向阻塞下载；满载时丢弃最旧样本，保持固定内存上界
        entries.entries.firstOrNull()?.key?.let(entries::remove)
    }

    private fun nextRevision(): Long {
        nextRevision = if (nextRevision == Long.MAX_VALUE) 1L else nextRevision + 1L
        return nextRevision
    }

    private fun tokenFor(entry: Entry): DownloadOperationTraceToken {
        return DownloadOperationTraceToken(
            operationId = entry.operationId,
            attemptId = entry.attemptId,
            revision = entry.revision,
            accepted = true
        )
    }

    private fun tokenForDropped(
        operationId: String,
        attemptId: Long?
    ): DownloadOperationTraceToken {
        return DownloadOperationTraceToken(
            operationId = operationId,
            attemptId = attemptId,
            revision = 0L,
            accepted = false
        )
    }

    private fun snapshotLocked(entry: Entry): DownloadOperationTiming {
        val marks = buildMap {
            DownloadOperationTracePhase.entries.forEach { phase ->
                entry.marksNs[phase.ordinal]
                    .takeIf { value -> value != UNSET_MARK_NS }
                    ?.let { value -> put(phase, value) }
            }
        }
        return DownloadOperationTiming(
            operationId = entry.operationId,
            attemptId = entry.attemptId,
            revision = entry.revision,
            marksNs = marks
        )
    }

    private fun publish(snapshot: DownloadOperationTiming?) {
        if (snapshot == null) return
        runCatching { onSnapshot(snapshot) }
    }

    private companion object {
        private const val DEFAULT_MAX_OPERATIONS = 1_000
        private const val UNSET_MARK_NS = Long.MIN_VALUE
    }
}

/** 进程内采样入口；诊断观察者不是下载状态 owner，异常必须被隔离 */
internal object DownloadOperationTrace {
    private val observer = AtomicReference<(DownloadOperationTiming) -> Unit>({})
    private val collector = DownloadOperationTimingCollector(
        onSnapshot = { timing ->
            runCatching { observer.get().invoke(timing) }
        }
    )

    fun installObserver(nextObserver: (DownloadOperationTiming) -> Unit) {
        observer.set(nextObserver)
    }

    fun begin(
        operationId: String,
        attemptId: Long? = null
    ): DownloadOperationTraceToken? = collector.begin(operationId, attemptId)

    fun mark(
        token: DownloadOperationTraceToken?,
        phase: DownloadOperationTracePhase
    ): DownloadOperationTiming? = collector.mark(token, phase)

    fun end(token: DownloadOperationTraceToken?): DownloadOperationTiming? =
        collector.end(token)

    fun mark(
        operationId: String,
        attemptId: Long?,
        phase: DownloadOperationTracePhase
    ): DownloadOperationTiming? = collector.mark(operationId, attemptId, phase)

    fun snapshot(token: DownloadOperationTraceToken?): DownloadOperationTiming? =
        collector.snapshot(token)

    fun snapshot(
        operationId: String,
        attemptId: Long?
    ): DownloadOperationTiming? = collector.snapshot(operationId, attemptId)

    internal fun size(): Int = collector.size()

    internal fun clearForTests() {
        collector.clearForTests()
        observer.set({})
    }
}
