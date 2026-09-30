package moe.ouom.neriplayer.core.download.resource.permit

import kotlinx.coroutines.CompletableDeferred

internal enum class DownloadPermitWaiterState {
    WAITING, GRANTED, DELIVERED, CANCELLED
}

internal class DownloadPermitWaiter(val ownerKey: String, var allowSingleOverflow: Boolean) {
    val completion = CompletableDeferred<DownloadTransferPermitRegistry.Permit>()
    var permit: DownloadTransferPermitRegistry.Permit? = null
    var state = DownloadPermitWaiterState.WAITING

    fun isWaiting(): Boolean = state == DownloadPermitWaiterState.WAITING && completion.isActive
}

/** 与活动 permit 共用 registry 的状态锁，交接和取消不会分别记账 */
internal class DownloadPermitWaitQueue {
    private val waiters = ArrayDeque<DownloadPermitWaiter>()

    fun isNotEmpty(): Boolean = waiters.isNotEmpty()
    fun add(waiter: DownloadPermitWaiter) = waiters.addLast(waiter)
    fun remove(waiter: DownloadPermitWaiter): Boolean = waiters.remove(waiter)
    fun ownerKeys(): List<String> = waiters.map(DownloadPermitWaiter::ownerKey)
    fun containsOwner(key: String): Boolean = waiters.any { it.ownerKey == key }

    fun findWaiting(matches: (String) -> Boolean): DownloadPermitWaiter? =
        waiters.firstOrNull { matches(it.ownerKey) && it.isWaiting() }

    fun next(hasBaseCapacity: Boolean, overflowActive: Boolean): DownloadPermitWaiter? {
        val priority = waiters.firstOrNull { it.allowSingleOverflow && it.isWaiting() }
        return when {
            hasBaseCapacity -> priority ?: waiters.firstOrNull(DownloadPermitWaiter::isWaiting)
            !overflowActive -> priority
            else -> null
        }
    }

    fun discardInactive() {
        waiters.removeAll { !it.isWaiting() }
    }
}
