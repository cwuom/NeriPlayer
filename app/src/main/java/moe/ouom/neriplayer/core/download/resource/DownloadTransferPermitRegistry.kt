package moe.ouom.neriplayer.core.download.resource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一拥有持久下载引擎使用的传输槽位
 *
 * 普通请求使用先进先出的等待队列，用户手动重试可以优先使用唯一的临时溢出槽位
 * 取消和立即重排因此具有确定性，每个 permit 也始终只有一个 owner
 */
internal class DownloadTransferPermitRegistry(
    private val maxParallelism: Int,
    private val activityGraceNs: Long = DEFAULT_ACTIVITY_GRACE_NS,
    private val nowNs: () -> Long = System::nanoTime,
    private val onSnapshotChanged: (Snapshot) -> Unit = {}
) {
    init {
        require(maxParallelism > 0) { "maxParallelism must be positive" }
        require(activityGraceNs >= 0L) { "activityGraceNs must not be negative" }
    }

    data class Snapshot(
        val requestedParallelism: Int,
        val effectiveParallelism: Int,
        val limitReason: String,
        val heldPermitOwners: Set<String>,
        val activeTransferOwners: Set<String>,
        val progressingTransferOwners: Set<String>,
        val waitingOwners: List<String>,
        val reportedBytesByOwner: Map<String, Long>
    ) {
        val permitCount: Int
            get() = heldPermitOwners.size

        val activeTransferCount: Int
            get() = activeTransferOwners.size

        val progressingTransferCount: Int
            get() = progressingTransferOwners.size

        val waitingCount: Int
            get() = waitingOwners.size
    }

    class Permit internal constructor(
        private val registry: DownloadTransferPermitRegistry,
        internal val ownerKey: String,
        internal val generation: Long
    ) : AutoCloseable {
        private val released = AtomicBoolean(false)

        fun markNetworkIoStarted() {
            registry.markNetworkIoStarted(this)
        }

        fun markNetworkIoFinished() {
            registry.markNetworkIoFinished(this)
        }

        /** 读取仍在进行但本次没有新增可见字节时，刷新看门狗心跳 */
        fun markNetworkActivity() {
            registry.markNetworkActivity(this)
        }

        fun recordProgress(absoluteBytes: Long): Boolean {
            return registry.recordProgress(this, absoluteBytes)
        }

        fun release() {
            if (released.compareAndSet(false, true)) {
                registry.release(this)
            }
        }

        override fun close() {
            release()
        }
    }

    private class Waiter(
        val ownerKey: String,
        var allowSingleOverflow: Boolean
    ) {
        val completion = CompletableDeferred<Permit>()
        var permit: Permit? = null
        var state: WaiterState = WaiterState.WAITING
    }

    /** 交接状态必须在 stateLock 下改变，避免取消线程回收已交给调用方的 permit */
    private enum class WaiterState {
        WAITING,
        GRANTED,
        DELIVERED,
        CANCELLED
    }

    private data class ActivePermit(
        val permit: Permit,
        val usesOverflow: Boolean = false,
        var networkIoActive: Boolean = false,
        var hasProgress: Boolean = false,
        var lastProgressNs: Long = 0L,
        var lastActivityNs: Long = 0L,
        var lastReportedBytes: Long = 0L
    )

    // 所有 owner、等待者和限额变化都在同一把锁下完成，避免双重记账
    private val stateLock = Any()
    private val waiters = ArrayDeque<Waiter>()
    private val activeByOwner = LinkedHashMap<String, ActivePermit>()
    private var nextGeneration = 0L
    private var requestedLimit = maxParallelism
    private var effectiveLimit = maxParallelism
    private var limitReason = INITIAL_LIMIT_REASON
    private var configurationRevision = Long.MIN_VALUE

    /**
     * 按先进先出顺序返回 permit。已取消的等待者会被移除，取消后不会再占用槽位
     */
    suspend fun acquire(
        ownerKey: String,
        configuredParallelism: Int? = null,
        reason: String = USER_SETTING_REASON,
        configurationRevision: Long? = null,
        allowSingleOverflow: Boolean = false
    ): Permit {
        val normalizedOwnerKey = ownerKey.trim()
        require(normalizedOwnerKey.isNotEmpty()) { "ownerKey must not be blank" }
        val waiter = Waiter(normalizedOwnerKey, allowSingleOverflow)
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            val limitChanged = configuredParallelism?.let { requested ->
                updateLimitLocked(requested, reason, configurationRevision)
            } ?: false
            check(normalizedOwnerKey !in activeByOwner) {
                "owner already holds a transfer permit: $normalizedOwnerKey"
            }
            check(waiters.none { it.ownerKey == normalizedOwnerKey }) {
                "owner is already waiting for a transfer permit: $normalizedOwnerKey"
            }
            waiters.addLast(waiter)
            val drained = drainLocked()
            if (limitChanged || drained) {
                snapshotToNotify = snapshotLocked(nowNs())
            }
        }
        notifySnapshot(snapshotToNotify)
        // 把协程取消直接接到等待者上，避免“取消回调尚未运行时”同 owner 重排
        // 的短暂竞态。finally 仍保留幂等兜底，覆盖无 Job 上下文和异常退出。
        val cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) {
                cancelWaiter(waiter)?.release()
            }
        }
        return try {
            val permit = waiter.completion.await()
            synchronized(stateLock) {
                if (waiter.state != WaiterState.GRANTED || waiter.permit !== permit) {
                    throw CancellationException("transfer permit handoff was cancelled")
                }
                // 从这里开始取消回调不再替调用方释放 permit，最终释放由调用方负责
                waiter.state = WaiterState.DELIVERED
            }
            permit
        } catch (cancellation: CancellationException) {
            // await 被取消后必须先从 FIFO 队列移除，再允许后继 owner 取槽
            cancelWaiter(waiter)?.release()
            throw cancellation
        } finally {
            // continuation 可能在 complete 后、交接前被取消，finally 负责最后一次兜底
            if (!waiter.completion.isCompleted) {
                cancelWaiter(waiter)?.release()
            }
            cancellationHandle?.dispose()
        }
    }

    /** 把已经进入等待队列的手动重试提升到唯一的临时溢出槽位 */
    fun promoteWaitingOwner(ownerKey: String): Boolean {
        val normalizedOwnerKey = ownerKey.trim().takeIf(String::isNotEmpty) ?: return false
        return promoteWaitingOwnerMatching { candidate -> candidate == normalizedOwnerKey }
    }

    /** attempt 可能已在宿主内刷新，按稳定 operation 身份提升当前等待者 */
    fun promoteWaitingOperation(operationId: String): Boolean {
        val normalizedOperationId = operationId.trim().takeIf(String::isNotEmpty) ?: return false
        val ownerPrefix = "$normalizedOperationId#"
        return promoteWaitingOwnerMatching { candidate -> candidate.startsWith(ownerPrefix) }
    }

    private fun promoteWaitingOwnerMatching(matches: (String) -> Boolean): Boolean {
        var snapshotToNotify: Snapshot? = null
        val found = synchronized(stateLock) {
            if (activeByOwner.keys.any(matches)) {
                return@synchronized true
            }
            val waiter = waiters.firstOrNull { candidate ->
                matches(candidate.ownerKey) &&
                    candidate.state == WaiterState.WAITING &&
                    candidate.completion.isActive
            } ?: return@synchronized false
            waiter.allowSingleOverflow = true
            if (drainLocked()) {
                snapshotToNotify = snapshotLocked(nowNs())
            }
            true
        }
        notifySnapshot(snapshotToNotify)
        return found
    }

    fun updateConfiguredParallelism(
        requestedParallelism: Int,
        reason: String = USER_SETTING_REASON,
        configurationRevision: Long? = null
    ): Snapshot {
        var snapshotToNotify: Snapshot? = null
        val snapshot = synchronized(stateLock) {
            val changed = updateLimitLocked(
                requestedParallelism = requestedParallelism,
                reason = reason,
                configurationRevision = configurationRevision
            )
            val drained = drainLocked()
            if (changed || drained) {
                snapshotToNotify = snapshotLocked(nowNs())
            }
            snapshotLocked(nowNs())
        }
        notifySnapshot(snapshotToNotify)
        return snapshot
    }

    fun snapshot(atNs: Long = nowNs()): Snapshot {
        synchronized(stateLock) {
            return snapshotLocked(atNs)
        }
    }

    fun recordProgress(ownerKey: String, absoluteBytes: Long): Boolean {
        if (absoluteBytes < 0L) return false
        synchronized(stateLock) {
            val active = activeByOwner[ownerKey.trim()] ?: return false
            return recordProgressLocked(active, absoluteBytes)
        }
    }

    /**
     * 只接受当前 permit generation 的进度，防止旧回调污染同 owner 的新尝试
     */
    fun recordProgress(
        ownerKey: String,
        generation: Long,
        absoluteBytes: Long
    ): Boolean {
        if (absoluteBytes < 0L) return false
        synchronized(stateLock) {
            val active = activeByOwner[ownerKey.trim()]
                ?.takeIf { it.permit.generation == generation }
                ?: return false
            return recordProgressLocked(active, absoluteBytes)
        }
    }

    /** 刷新当前 permit 的网络读取心跳，但不虚增已传输字节 */
    fun markNetworkActivity(ownerKey: String, generation: Long): Boolean {
        synchronized(stateLock) {
            val active = activeByOwner[ownerKey.trim()]
                ?.takeIf { it.permit.generation == generation && it.networkIoActive }
                ?: return false
            active.lastActivityNs = nowNs()
            return true
        }
    }

    /**
     * 判断当前网络 I/O 是否已经超过无进展窗口
     *
     * 这里只做只读判断，不会替调用方释放 permit。真正的取消和重试必须由传输层完成，
     * 这样不会让仍在写文件的协程失去 owner 后继续修改新 attempt
     */
    fun isProgressStale(
        ownerKey: String,
        generation: Long,
        staleAfterNs: Long,
        atNs: Long = nowNs()
    ): Boolean {
        if (staleAfterNs <= 0L) return false
        synchronized(stateLock) {
            val active = activeByOwner[ownerKey.trim()]
                ?.takeIf { it.permit.generation == generation }
                ?: return false
            if (!active.networkIoActive) return false
            return (atNs - active.lastActivityNs).coerceAtLeast(0L) >= staleAfterNs
        }
    }

    private fun updateLimitLocked(
        requestedParallelism: Int,
        reason: String,
        configurationRevision: Long?
    ): Boolean {
        if (
            configurationRevision != null &&
            configurationRevision < this.configurationRevision
        ) {
            return false
        }
        val normalizedReason = reason.trim().ifBlank { UNSPECIFIED_LIMIT_REASON }
        val normalizedLimit = requestedParallelism.coerceIn(1, maxParallelism)
        val changed = this.requestedLimit != requestedParallelism ||
            effectiveLimit != normalizedLimit ||
            limitReason != normalizedReason
        this.requestedLimit = requestedParallelism
        effectiveLimit = normalizedLimit
        limitReason = normalizedReason
        if (configurationRevision != null) {
            this.configurationRevision = configurationRevision
        }
        return changed
    }

    private fun drainLocked(): Boolean {
        var granted = false
        while (waiters.isNotEmpty()) {
            val normalActiveCount = activeByOwner.values.count { active ->
                !active.usesOverflow
            }
            val hasBaseCapacity = normalActiveCount < effectiveLimit
            val overflowActive = activeByOwner.values.any(ActivePermit::usesOverflow)
            val priorityWaiter = waiters.firstOrNull { waiter ->
                waiter.allowSingleOverflow &&
                    waiter.state == WaiterState.WAITING &&
                    waiter.completion.isActive
            }
            val waiter = when {
                hasBaseCapacity -> priorityWaiter ?: waiters.firstOrNull { candidate ->
                    candidate.state == WaiterState.WAITING && candidate.completion.isActive
                }
                !overflowActive -> priorityWaiter
                else -> null
            }
            if (waiter == null) {
                waiters.removeAll { candidate ->
                    candidate.state != WaiterState.WAITING || !candidate.completion.isActive
                }
                break
            }
            waiters.remove(waiter)
            val usesOverflow = !hasBaseCapacity
            val permit = Permit(
                registry = this,
                ownerKey = waiter.ownerKey,
                generation = ++nextGeneration
            )
            waiter.permit = permit
            activeByOwner[waiter.ownerKey] = ActivePermit(
                permit = permit,
                usesOverflow = usesOverflow
            )
            waiter.state = WaiterState.GRANTED
            if (waiter.completion.complete(permit)) {
                granted = true
            } else {
                waiter.state = WaiterState.CANCELLED
                activeByOwner.remove(waiter.ownerKey)
            }
        }
        return granted
    }

    private fun markNetworkIoStarted(permit: Permit) {
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            val active = activeByOwner[permit.ownerKey]
                ?.takeIf { it.permit === permit }
                ?: return
            if (!active.networkIoActive) {
                active.networkIoActive = true
                val now = nowNs()
                active.lastProgressNs = now
                active.lastActivityNs = now
                snapshotToNotify = snapshotLocked(now)
            }
        }
        notifySnapshot(snapshotToNotify)
    }

    private fun markNetworkActivity(permit: Permit) {
        synchronized(stateLock) {
            val active = activeByOwner[permit.ownerKey]
                ?.takeIf { it.permit === permit && it.networkIoActive }
                ?: return
            active.lastActivityNs = nowNs()
        }
    }

    private fun markNetworkIoFinished(permit: Permit) {
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            val active = activeByOwner[permit.ownerKey]
                ?.takeIf { it.permit === permit }
                ?: return
            if (active.networkIoActive) {
                active.networkIoActive = false
                snapshotToNotify = snapshotLocked(nowNs())
            }
        }
        notifySnapshot(snapshotToNotify)
    }

    private fun recordProgress(permit: Permit, absoluteBytes: Long): Boolean {
        if (absoluteBytes < 0L) return false
        synchronized(stateLock) {
            val active = activeByOwner[permit.ownerKey]
                ?.takeIf { it.permit === permit }
                ?: return false
            return recordProgressLocked(active, absoluteBytes)
        }
    }

    private fun recordProgressLocked(
        active: ActivePermit,
        absoluteBytes: Long
    ): Boolean {
        if (absoluteBytes < active.lastReportedBytes) return false
        val changed = absoluteBytes > active.lastReportedBytes
        active.lastReportedBytes = absoluteBytes
        if (changed) {
            active.hasProgress = true
            val now = nowNs()
            active.lastProgressNs = now
            active.lastActivityNs = now
        }
        return changed
    }

    private fun release(permit: Permit) {
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            val active = activeByOwner[permit.ownerKey]
                ?.takeIf { it.permit === permit }
                ?: return
            activeByOwner.remove(permit.ownerKey)
            drainLocked()
            snapshotToNotify = snapshotLocked(nowNs())
        }
        notifySnapshot(snapshotToNotify)
    }

    private fun cancelWaiter(waiter: Waiter): Permit? {
        var permitToRelease: Permit? = null
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            when (waiter.state) {
                WaiterState.WAITING -> {
                    waiter.state = WaiterState.CANCELLED
                    val removed = waiters.remove(waiter)
                    if (removed) {
                        drainLocked()
                        snapshotToNotify = snapshotLocked(nowNs())
                    }
                }

                WaiterState.GRANTED -> {
                    waiter.state = WaiterState.CANCELLED
                    val active = activeByOwner[waiter.ownerKey]
                    if (active?.permit === waiter.permit) {
                        activeByOwner.remove(waiter.ownerKey)
                        permitToRelease = waiter.permit
                        drainLocked()
                        snapshotToNotify = snapshotLocked(nowNs())
                    }
                }

                WaiterState.DELIVERED,
                WaiterState.CANCELLED -> Unit
            }
        }
        notifySnapshot(snapshotToNotify)
        return permitToRelease
    }

    private fun snapshotLocked(atNs: Long): Snapshot {
        val activeTransferOwners = activeByOwner.values
            .filter { it.networkIoActive }
            .mapTo(linkedSetOf()) { it.permit.ownerKey }
        val progressingTransferOwners = activeByOwner.values
            .filter { active ->
                active.networkIoActive &&
                    active.hasProgress &&
                    (atNs - active.lastProgressNs).coerceAtLeast(0L) <= activityGraceNs
            }
            .mapTo(linkedSetOf()) { it.permit.ownerKey }
        val reportedBytes = activeByOwner
            .mapValues { (_, active) -> active.lastReportedBytes }
        return Snapshot(
            requestedParallelism = requestedLimit,
            effectiveParallelism = effectiveLimit,
            limitReason = limitReason,
            heldPermitOwners = activeByOwner.keys.toSet(),
            activeTransferOwners = activeTransferOwners,
            progressingTransferOwners = progressingTransferOwners,
            waitingOwners = waiters.map(Waiter::ownerKey),
            reportedBytesByOwner = reportedBytes
        )
    }

    private fun notifySnapshot(snapshot: Snapshot?) {
        if (snapshot == null) return
        runCatching { onSnapshotChanged(snapshot) }
    }

    companion object {
        private const val INITIAL_LIMIT_REASON = "initial"
        private const val USER_SETTING_REASON = "user_setting"
        private const val UNSPECIFIED_LIMIT_REASON = "unspecified"
        private const val DEFAULT_ACTIVITY_GRACE_NS = 2_000_000_000L

        fun ownerKey(
            operationId: String?,
            attemptId: Long?,
            stableKey: String? = null
        ): String {
            val normalizedOperationId = operationId?.trim().orEmpty()
                .ifBlank { "anonymous" }
            val normalizedStableKey = stableKey?.trim().orEmpty()
            return if (normalizedStableKey.isBlank()) {
                "$normalizedOperationId#${attemptId ?: 0L}"
            } else {
                "$normalizedOperationId#${attemptId ?: 0L}@$normalizedStableKey"
            }
        }
    }
}
