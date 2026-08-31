package moe.ouom.neriplayer.core.download.execution

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * keeps deferred host handoffs fair without making cancellation walk a large queue
 */
internal class DeferredDownloadScheduleQueue {
    private val requests = ConcurrentHashMap<String, DownloadExecutionRequest>()
    private val readyOperationIds = ConcurrentLinkedQueue<String>()
    private val queuedOperationIds = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(request: DownloadExecutionRequest) {
        requests[request.operationId] = request
        offerIfAbsent(request.operationId)
    }

    fun poll(): DownloadExecutionRequest? {
        while (true) {
            val operationId = readyOperationIds.poll() ?: return null
            queuedOperationIds.remove(operationId)
            val request = requests[operationId] ?: continue
            return request
        }
    }

    fun requeue(request: DownloadExecutionRequest) {
        if (requests[request.operationId] !== request) {
            return
        }
        offerIfAbsent(request.operationId)
    }

    fun remove(operationId: String) {
        requests.remove(operationId)
        queuedOperationIds.remove(operationId)
    }

    fun remove(request: DownloadExecutionRequest) {
        if (requests.remove(request.operationId, request)) {
            queuedOperationIds.remove(request.operationId)
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
        readyOperationIds.clear()
        queuedOperationIds.clear()
    }

    private fun offerIfAbsent(operationId: String) {
        if (queuedOperationIds.add(operationId)) {
            readyOperationIds.offer(operationId)
        }
    }
}
