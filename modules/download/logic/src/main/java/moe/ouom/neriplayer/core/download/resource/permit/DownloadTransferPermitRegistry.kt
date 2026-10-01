package moe.ouom.neriplayer.core.download.resource.permit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 统一拥有持久下载引擎使用的传输槽位
 *
 * 普通请求使用先进先出的等待队列，用户手动重试可以优先使用唯一的临时溢出槽位
 * 取消和立即重排因此具有确定性，每个 permit 也始终只有一个 owner
 */
class DownloadTransferPermitRegistry(
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
        val ownerKey: String,
        val generation: Long
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

    // 所有 owner、等待者和限额变化都在同一把锁下完成，避免双重记账
    private val stateLock = Any()
    private val waiters = DownloadPermitWaitQueue()
    private val activeByOwner = LinkedHashMap<String, DownloadPermitActivity>()
    private var nextGeneration = 0L
    private val limits = DownloadPermitLimit(maxParallelism)

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
        val waiter = DownloadPermitWaiter(normalizedOwnerKey, allowSingleOverflow)
        val snapshot = synchronized(stateLock) {
            registerWaiterLocked(waiter, configuredParallelism, reason, configurationRevision)
        }
        notifySnapshot(snapshot)
        return awaitPermit(waiter)
    }

    private fun registerWaiterLocked(
        waiter: DownloadPermitWaiter,
        configuredParallelism: Int?,
        reason: String,
        configurationRevision: Long?
    ): Snapshot? {
        val limitChanged = configuredParallelism?.let {
            updateLimitLocked(it, reason, configurationRevision)
        } ?: false
        check(waiter.ownerKey !in activeByOwner) {
            "owner already holds a transfer permit: ${waiter.ownerKey}"
        }
        check(!waiters.containsOwner(waiter.ownerKey)) {
            "owner is already waiting for a transfer permit: ${waiter.ownerKey}"
        }
        waiters.add(waiter)
        val drained = drainLocked()
        return if (limitChanged || drained) snapshotLocked(nowNs()) else null
    }

    private suspend fun awaitPermit(waiter: DownloadPermitWaiter): Permit {
        // 把协程取消直接接到等待者上，避免“取消回调尚未运行时”同 owner 重排
        // 的短暂竞态。finally 仍保留幂等兜底，覆盖无 Job 上下文和异常退出。
        val cancellationHandle = registerWaiterCancellation(waiter)
        return try {
            val permit = waiter.completion.await()
            synchronized(stateLock) { deliverPermitLocked(waiter, permit) }
            permit
        } catch (cancellation: CancellationException) {
            // await 被取消后必须先从 FIFO 队列移除，再允许后继 owner 取槽
            cancelWaiterAndRelease(waiter)
            throw cancellation
        } finally {
            // continuation 可能在 complete 后、交接前被取消，finally 负责最后一次兜底
            if (!waiter.completion.isCompleted) {
                cancelWaiterAndRelease(waiter)
            }
            cancellationHandle?.dispose()
        }
    }

    private suspend fun registerWaiterCancellation(waiter: DownloadPermitWaiter): DisposableHandle? =
        currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            cancelWaiterAfterFailure(waiter, cause)
        }

    private fun cancelWaiterAfterFailure(waiter: DownloadPermitWaiter, cause: Throwable?) {
        if (cause != null) cancelWaiterAndRelease(waiter)
    }

    private fun cancelWaiterAndRelease(waiter: DownloadPermitWaiter) {
        cancelWaiter(waiter)?.release()
    }

    private fun deliverPermitLocked(waiter: DownloadPermitWaiter, permit: Permit) {
        if (waiter.state != DownloadPermitWaiterState.GRANTED || waiter.permit !== permit) {
            throw CancellationException("transfer permit handoff was cancelled")
        }
        // 交接后由调用方释放 permit，取消回调不能回收正在写文件的槽位
        waiter.state = DownloadPermitWaiterState.DELIVERED
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
            val waiter = waiters.findWaiting(matches) ?: return@synchronized false
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
            val active = activeForGeneration(ownerKey, generation) ?: return false
            return recordProgressLocked(active, absoluteBytes)
        }
    }

    /** 刷新当前 permit 的网络读取心跳，但不虚增已传输字节 */
    fun markNetworkActivity(ownerKey: String, generation: Long): Boolean {
        synchronized(stateLock) {
            val active = activeForGeneration(ownerKey, generation) ?: return false
            return active.markNetworkActivity(nowNs)
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
            val active = activeForGeneration(ownerKey, generation) ?: return false
            return active.isStale(staleAfterNs, atNs)
        }
    }

    private fun updateLimitLocked(
        requestedParallelism: Int,
        reason: String,
        configurationRevision: Long?
    ): Boolean = limits.update(requestedParallelism, reason, configurationRevision)

    private fun drainLocked(): Boolean {
        var granted = false
        while (waiters.isNotEmpty()) {
            val normalActiveCount = activeByOwner.values.count { !it.usesOverflow }
            val hasBaseCapacity = normalActiveCount < limits.effective
            val overflowActive = activeByOwner.values.any(DownloadPermitActivity::usesOverflow)
            val waiter = waiters.next(hasBaseCapacity, overflowActive)
            if (waiter == null) {
                waiters.discardInactive()
                break
            }
            if (grantWaiterLocked(waiter, usesOverflow = !hasBaseCapacity)) granted = true
        }
        return granted
    }

    private fun grantWaiterLocked(waiter: DownloadPermitWaiter, usesOverflow: Boolean): Boolean {
        waiters.remove(waiter)
        val permit = Permit(this, waiter.ownerKey, ++nextGeneration)
        waiter.permit = permit
        activeByOwner[waiter.ownerKey] = DownloadPermitActivity(permit, usesOverflow)
        waiter.state = DownloadPermitWaiterState.GRANTED
        if (waiter.completion.complete(permit)) return true
        waiter.state = DownloadPermitWaiterState.CANCELLED
        activeByOwner.remove(waiter.ownerKey)
        return false
    }

    private fun markNetworkIoStarted(permit: Permit) {
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            val active = activeForPermit(permit) ?: return
            val now = active.startNetworkIo(nowNs) ?: return@synchronized
            snapshotToNotify = snapshotLocked(now)
        }
        notifySnapshot(snapshotToNotify)
    }

    private fun markNetworkActivity(permit: Permit) {
        synchronized(stateLock) {
            activeForPermit(permit)?.markNetworkActivity(nowNs)
        }
    }

    private fun markNetworkIoFinished(permit: Permit) {
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            val active = activeForPermit(permit) ?: return
            if (active.finishNetworkIo()) snapshotToNotify = snapshotLocked(nowNs())
        }
        notifySnapshot(snapshotToNotify)
    }

    private fun recordProgress(permit: Permit, absoluteBytes: Long): Boolean {
        if (absoluteBytes < 0L) return false
        synchronized(stateLock) {
            val active = activeForPermit(permit) ?: return false
            return recordProgressLocked(active, absoluteBytes)
        }
    }

    private fun recordProgressLocked(active: DownloadPermitActivity, absoluteBytes: Long): Boolean =
        active.recordProgress(absoluteBytes, nowNs)

    private fun activeForPermit(permit: Permit): DownloadPermitActivity? =
        activeByOwner[permit.ownerKey]?.takeIf { it.permit === permit }

    private fun activeForGeneration(ownerKey: String, generation: Long): DownloadPermitActivity? =
        activeByOwner[ownerKey.trim()]?.takeIf { it.permit.generation == generation }

    private fun release(permit: Permit) {
        var snapshotToNotify: Snapshot? = null
        synchronized(stateLock) {
            if (activeByOwner[permit.ownerKey]?.permit !== permit) return
            activeByOwner.remove(permit.ownerKey)
            drainLocked()
            snapshotToNotify = snapshotLocked(nowNs())
        }
        notifySnapshot(snapshotToNotify)
    }

    private fun cancelWaiter(waiter: DownloadPermitWaiter): Permit? {
        val result = synchronized(stateLock) {
            when (waiter.state) {
                DownloadPermitWaiterState.WAITING -> cancelQueuedWaiterLocked(waiter)
                DownloadPermitWaiterState.GRANTED -> cancelGrantedWaiterLocked(waiter)
                DownloadPermitWaiterState.DELIVERED,
                DownloadPermitWaiterState.CANCELLED -> WaiterCancellation()
            }
        }
        notifySnapshot(result.snapshot)
        return result.permit
    }

    private fun cancelQueuedWaiterLocked(waiter: DownloadPermitWaiter): WaiterCancellation {
        waiter.state = DownloadPermitWaiterState.CANCELLED
        if (!waiters.remove(waiter)) return WaiterCancellation()
        drainLocked()
        return WaiterCancellation(snapshot = snapshotLocked(nowNs()))
    }

    private fun cancelGrantedWaiterLocked(waiter: DownloadPermitWaiter): WaiterCancellation {
        waiter.state = DownloadPermitWaiterState.CANCELLED
        if (activeByOwner[waiter.ownerKey]?.permit !== waiter.permit) return WaiterCancellation()
        activeByOwner.remove(waiter.ownerKey)
        drainLocked()
        return WaiterCancellation(permit = waiter.permit, snapshot = snapshotLocked(nowNs()))
    }

    private data class WaiterCancellation(val permit: Permit? = null, val snapshot: Snapshot? = null)

    private fun snapshotLocked(atNs: Long): Snapshot {
        val activeTransferOwners = activeByOwner.values
            .filter { it.networkIoActive }
            .mapTo(linkedSetOf()) { it.permit.ownerKey }
        val progressingTransferOwners = activeByOwner.values
            .filter { active ->
                active.isProgressing(atNs, activityGraceNs)
            }
            .mapTo(linkedSetOf()) { it.permit.ownerKey }
        val reportedBytes = activeByOwner
            .mapValues { (_, active) -> active.lastReportedBytes }
        return Snapshot(
            requestedParallelism = limits.requested,
            effectiveParallelism = limits.effective,
            limitReason = limits.reason,
            heldPermitOwners = activeByOwner.keys.toSet(),
            activeTransferOwners = activeTransferOwners,
            progressingTransferOwners = progressingTransferOwners,
            waitingOwners = waiters.ownerKeys(),
            reportedBytesByOwner = reportedBytes
        )
    }

    private fun notifySnapshot(snapshot: Snapshot?) {
        if (snapshot == null) return
        runCatching { onSnapshotChanged(snapshot) }
    }

    companion object {
        private const val USER_SETTING_REASON = "user_setting"
        private const val DEFAULT_ACTIVITY_GRACE_NS = 2_000_000_000L

        private fun normalizedOwnerOperationId(operationId: String?): String =
            operationId?.trim().orEmpty().ifBlank { "anonymous" }

        fun ownerKey(
            operationId: String?,
            attemptId: Long?,
            stableKey: String? = null
        ): String {
            val normalizedOperationId = normalizedOwnerOperationId(operationId)
            val normalizedStableKey = stableKey?.trim().orEmpty()
            val attempt = attemptId ?: 0L
            return if (normalizedStableKey.isBlank()) {
                "$normalizedOperationId#$attempt"
            } else {
                "$normalizedOperationId#$attempt@$normalizedStableKey"
            }
        }
    }
}
