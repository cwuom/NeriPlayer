package moe.ouom.neriplayer.core.download.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 保持宿主延后交接公平，同时避免取消时遍历无界队列
 */
internal class DeferredDownloadScheduleQueue(
    private val maxRequests: Int = DEFAULT_MAX_REQUESTS
) {
    init {
        require(maxRequests > 0) { "maxRequests must be positive" }
    }

    private val requests = ConcurrentHashMap<String, DownloadExecutionRequest>()
    private val insertionOrder = ConcurrentLinkedQueue<String>()
    private val readyOperationIds = ConcurrentLinkedQueue<String>()
    private val queuedOperationIds = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(request: DownloadExecutionRequest) {
        val previous = requests.put(request.operationId, request)
        if (previous == null) {
            // 只记录首次入队顺序，更新同一个 operation 不应制造重复节点
            insertionOrder.offer(request.operationId)
        }
        offerIfAbsent(request.operationId)
        evictOverflow()
    }

    fun poll(): DownloadExecutionRequest? {
        while (true) {
            val operationId = readyOperationIds.poll() ?: return null
            queuedOperationIds.remove(operationId)
            insertionOrder.remove(operationId)
            val request = requests[operationId] ?: continue
            return request
        }
    }

    fun requeue(request: DownloadExecutionRequest) {
        if (requests[request.operationId] !== request) {
            return
        }
        if (!insertionOrder.contains(request.operationId)) {
            insertionOrder.offer(request.operationId)
        }
        offerIfAbsent(request.operationId)
        evictOverflow()
    }

    fun remove(operationId: String) {
        requests.remove(operationId)
        queuedOperationIds.remove(operationId)
        insertionOrder.remove(operationId)
        readyOperationIds.remove(operationId)
    }

    fun remove(request: DownloadExecutionRequest) {
        if (requests.remove(request.operationId, request)) {
            queuedOperationIds.remove(request.operationId)
            insertionOrder.remove(request.operationId)
            readyOperationIds.remove(request.operationId)
        }
    }

    fun removeAll(operationIds: Collection<String>) {
        operationIds.forEach(::remove)
    }

    fun operationIds(): Set<String> = requests.keys.toSet()

    fun isEmpty(): Boolean = requests.isEmpty()

    fun size(): Int = requests.size

    fun clear() {
        requests.clear()
        insertionOrder.clear()
        readyOperationIds.clear()
        queuedOperationIds.clear()
    }

    private fun offerIfAbsent(operationId: String) {
        if (queuedOperationIds.add(operationId)) {
            readyOperationIds.offer(operationId)
        }
    }

    private fun evictOverflow() {
        while (requests.size > maxRequests) {
            val oldestOperationId = insertionOrder.poll() ?: return
            if (requests.remove(oldestOperationId) != null) {
                queuedOperationIds.remove(oldestOperationId)
                readyOperationIds.remove(oldestOperationId)
            }
        }
    }

    private companion object {
        private const val DEFAULT_MAX_REQUESTS = 1_024
    }
}
