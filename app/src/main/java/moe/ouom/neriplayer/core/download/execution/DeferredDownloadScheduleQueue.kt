package moe.ouom.neriplayer.core.download.execution

import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * 保持宿主延后交接公平，并让取消和更新不遍历历史 ready 节点
 */
internal class DeferredDownloadScheduleQueue(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS
) {
    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
    }

    private data class RequestEntry(
        val request: DownloadExecutionRequest,
        val generation: Long,
        val orderGeneration: Long,
        val ready: Boolean
    )

    private data class ReadyNode(
        val operationId: String,
        val generation: Long
    )

    private data class OrderNode(
        val operationId: String,
        val generation: Long
    )

    /** 只在 lock 内访问，LinkedHashMap 的插入顺序表示仍处于 deferred 状态的先后 */
    private val lock = Any()
    private val requests = LinkedHashMap<String, RequestEntry>()
    private val readyNodes = ArrayDeque<ReadyNode>()
    private val orderNodes = ArrayDeque<OrderNode>()
    private var nextGeneration = 0L
    private var compactionCount = 0L

    fun enqueue(request: DownloadExecutionRequest) = synchronized(lock) {
        val current = requests[request.operationId]
        val entry = if (current == null) {
            newEntry(request = request, ready = true)
                .also { added ->
                    orderNodes.addLast(
                        OrderNode(request.operationId, added.orderGeneration)
                    )
                }
        } else {
            current.copy(
                request = request,
                generation = nextGeneration(),
                // 正在等待的 operation 保留最初的公平顺序
                ready = current.ready
            )
        }
        requests[request.operationId] = entry
        if (entry.ready) {
            readyNodes.addLast(ReadyNode(request.operationId, entry.generation))
        }
        evictOverflow()
        compactIfNeeded()
    }

    fun poll(): DownloadExecutionRequest? = synchronized(lock) {
        while (readyNodes.isNotEmpty()) {
            val node = readyNodes.removeFirst()
            val current = requests[node.operationId] ?: continue
            if (!current.ready || current.generation != node.generation) continue
            requests[node.operationId] = current.copy(ready = false)
            return current.request
        }
        null
    }

    fun requeue(request: DownloadExecutionRequest) = synchronized(lock) {
        val current = requests[request.operationId]
        if (current?.request !== request || current.ready) return@synchronized
        val entry = newEntry(request = request, ready = true)
        requests[request.operationId] = entry
        orderNodes.addLast(OrderNode(request.operationId, entry.orderGeneration))
        readyNodes.addLast(ReadyNode(request.operationId, entry.generation))
        evictOverflow()
        compactIfNeeded()
    }

    fun remove(operationId: String) = synchronized(lock) {
        requests.remove(operationId)
        compactIfNeeded()
    }

    fun remove(request: DownloadExecutionRequest) = synchronized(lock) {
        if (requests[request.operationId]?.request === request) {
            requests.remove(request.operationId)
            compactIfNeeded()
        }
    }

    fun removeAll(operationIds: Collection<String>) = synchronized(lock) {
        operationIds.forEach(requests::remove)
        compactIfNeeded()
    }

    fun operationIds(): Set<String> = synchronized(lock) { requests.keys.toSet() }

    fun isEmpty(): Boolean = synchronized(lock) { requests.isEmpty() }

    fun size(): Int = synchronized(lock) { requests.size }

    fun clear() = synchronized(lock) {
        requests.clear()
        readyNodes.clear()
        orderNodes.clear()
    }

    internal fun snapshot(): Snapshot = synchronized(lock) {
        Snapshot(
            requestCount = requests.size,
            readyNodeCount = readyNodes.size,
            orderNodeCount = orderNodes.size,
            compactionCount = compactionCount
        )
    }

    private fun newEntry(
        request: DownloadExecutionRequest,
        ready: Boolean
    ): RequestEntry {
        val generation = nextGeneration()
        return RequestEntry(
            request = request,
            generation = generation,
            orderGeneration = generation,
            ready = ready
        )
    }

    private fun nextGeneration(): Long {
        if (nextGeneration == Long.MAX_VALUE) {
            // generation 只用于进程内 stale-node fencing，回绕时先清空 transient queue
            clear()
        }
        nextGeneration += 1L
        return nextGeneration
    }

    private fun evictOverflow() {
        while (requests.size > maxRequests) {
            val node = nextCurrentOrderNode() ?: return
            val current = requests[node.operationId] ?: continue
            if (current.ready && current.orderGeneration == node.generation) {
                requests.remove(node.operationId)
            }
        }
    }

    private fun nextCurrentOrderNode(): OrderNode? {
        while (orderNodes.isNotEmpty()) {
            val node = orderNodes.removeFirst()
            val current = requests[node.operationId]
            if (current != null && current.ready && current.orderGeneration == node.generation) {
                return node
            }
        }
        return null
    }

    private fun compactIfNeeded() {
        if (
            readyNodes.size.toLong() <= maxNodeCount() &&
                orderNodes.size.toLong() <= maxNodeCount()
        ) {
            return
        }
        val compactReadyNodes = ArrayDeque<ReadyNode>()
        readyNodes.forEach { node ->
            val current = requests[node.operationId]
            if (current != null && current.ready && current.generation == node.generation) {
                compactReadyNodes.addLast(node)
            }
        }
        val compactOrderNodes = ArrayDeque<OrderNode>()
        orderNodes.forEach { node ->
            val current = requests[node.operationId]
            if (current != null && current.ready && current.orderGeneration == node.generation) {
                compactOrderNodes.addLast(node)
            }
        }
        readyNodes.clear()
        readyNodes.addAll(compactReadyNodes)
        orderNodes.clear()
        orderNodes.addAll(compactOrderNodes)
        compactionCount += 1L
    }

    private fun maxNodeCount(): Long = maxRequests.toLong() * 2L + STALE_NODE_ALLOWANCE

    internal data class Snapshot(
        val requestCount: Int,
        val readyNodeCount: Int,
        val orderNodeCount: Int,
        val compactionCount: Long
    )

    private companion object {
        private const val DEFAULT_MAX_REQUESTS = 1_024
        private const val STALE_NODE_ALLOWANCE = 16L
    }
}
