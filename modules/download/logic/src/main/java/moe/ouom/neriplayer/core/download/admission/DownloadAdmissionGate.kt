package moe.ouom.neriplayer.core.download.admission

import kotlinx.coroutines.CompletableDeferred

class DownloadAdmissionGate {
    @ConsistentCopyVisibility
    data class ClearToken internal constructor(
        internal val completion: CompletableDeferred<Unit>,
        val generation: Long,
        val ownsClear: Boolean
    )

    private data class GateSnapshot(
        val completion: CompletableDeferred<Unit>?,
        val generation: Long
    )

    private sealed interface AdmissionDecision {
        data class Wait(val completion: CompletableDeferred<Unit>) : AdmissionDecision
        data object Reject : AdmissionDecision
        data object Entered : AdmissionDecision
    }

    private val stateLock = Any()
    private var activeClear: CompletableDeferred<Unit>? = null
    private var generation = 0L
    private var activeAdmissions = 0
    private var idleCompletion = completedDeferred()

    fun ticket(): Long = synchronized(stateLock) { generation }

    fun openTicketOrNull(): Long? = synchronized(stateLock) {
        generation.takeIf { activeClear == null }
    }

    fun beginClear(): ClearToken = synchronized(stateLock) {
        activeClear?.let { completion ->
            return@synchronized ClearToken(
                completion = completion,
                generation = generation,
                ownsClear = false
            )
        }
        generation++
        val completion = CompletableDeferred<Unit>()
        activeClear = completion
        ClearToken(
            completion = completion,
            generation = generation,
            ownsClear = true
        )
    }

    suspend fun awaitOpen() {
        while (true) {
            val completion = snapshot().completion ?: return
            completion.await()
        }
    }

    suspend fun awaitClear(token: ClearToken) {
        token.completion.await()
    }

    /** 等待已登记的 mutation 完成，不持有准入状态锁 */
    suspend fun awaitIdle() {
        val completion = synchronized(stateLock) { idleCompletion }
        completion.await()
    }

    /** 只在内存状态中登记准入，挂起工作始终在状态锁外执行 */
    suspend fun admit(
        ticket: Long,
        block: suspend () -> Unit
    ): Boolean {
        while (true) {
            when (val decision = tryEnter(ticket)) {
                AdmissionDecision.Reject -> return false
                is AdmissionDecision.Wait -> decision.completion.await()
                AdmissionDecision.Entered -> {
                    try {
                        block()
                        return true
                    } finally {
                        leave()
                    }
                }
            }
        }
    }

    /** 清空工作不持有状态锁，generation 负责阻止旧票据重新发布 */
    suspend fun runClear(
        token: ClearToken,
        block: suspend () -> Unit
    ) {
        require(token.ownsClear) { "clear token does not own the active clear" }
        try {
            block()
        } finally {
            synchronized(stateLock) {
                if (activeClear === token.completion && generation == token.generation) {
                    activeClear = null
                }
            }
            token.completion.complete(Unit)
        }
    }

    private fun tryEnter(ticket: Long): AdmissionDecision = synchronized(stateLock) {
        if (generation != ticket) {
            return@synchronized AdmissionDecision.Reject
        }
        activeClear?.let { completion ->
            return@synchronized AdmissionDecision.Wait(completion)
        }
        if (activeAdmissions == 0) {
            idleCompletion = CompletableDeferred()
        }
        activeAdmissions++
        AdmissionDecision.Entered
    }

    private fun leave() {
        val completion = synchronized(stateLock) {
            check(activeAdmissions > 0) { "download admission underflow" }
            activeAdmissions--
            if (activeAdmissions == 0) idleCompletion else null
        }
        completion?.complete(Unit)
    }

    /**
     * 持久栅栏和 owner 快照已经覆盖旧任务后，后台 I/O 不应继续占用全局内存闸门
     *
     * 旧票据已在 beginClear 时失效；新的不同 stableKey 仍会在持久栅栏处做 owner
     * 复核，因此这里仅解除本进程的等待，不会让旧 generation 重新发布
     */
    fun detachClearForDurableRecovery(token: ClearToken): Boolean {
        require(token.ownsClear) { "clear token does not own the active clear" }
        val released = synchronized(stateLock) {
            if (activeClear === token.completion && generation == token.generation) {
                activeClear = null
                true
            } else {
                false
            }
        }
        if (released) {
            token.completion.complete(Unit)
        }
        return released
    }

    /** 持久栅栏落盘失败时也要释放本进程 gate，兼容旧调用方 */
    fun releaseFailedClear(token: ClearToken): Boolean =
        detachClearForDurableRecovery(token)

    private fun completedDeferred(): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { it.complete(Unit) }

    private fun snapshot(): GateSnapshot = synchronized(stateLock) {
        GateSnapshot(
            completion = activeClear,
            generation = generation
        )
    }
}
