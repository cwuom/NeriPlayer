package moe.ouom.neriplayer.core.download.storage.migration

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * coalesces receipt writes while keeping a bounded amount of recovery evidence
 * in memory. A flush is required before source cleanup can begin
 */
internal class ManagedDownloadMigrationCopyReceiptBatcher(
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
    private val persist: suspend (List<ManagedMigrationCopyReceipt>) -> Unit
) {
    private val mutex = Mutex()
    private val pending = linkedMapOf<String, ManagedMigrationCopyReceipt>()

    init {
        require(batchSize > 0) { "迁移复制凭据批量大小必须大于零" }
    }

    suspend fun add(receipt: ManagedMigrationCopyReceipt) {
        val batch = mutex.withLock {
            pending[receipt.sourceReference] = receipt
            if (pending.size >= batchSize) drainLocked() else null
        }
        persistBatch(batch)
    }

    suspend fun invalidate(sourceReference: String) {
        mutex.withLock {
            pending.remove(sourceReference.trim())
        }
    }

    suspend fun flush() {
        while (true) {
            val batch = mutex.withLock { drainLocked() }
            if (batch.isEmpty()) return
            persistBatch(batch)
        }
    }

    private suspend fun persistBatch(batch: List<ManagedMigrationCopyReceipt>?) {
        if (batch.isNullOrEmpty()) return
        try {
            persist(batch)
        } catch (error: Throwable) {
            mutex.withLock {
                batch.forEach { receipt ->
                    pending.putIfAbsent(receipt.sourceReference, receipt)
                }
            }
            throw error
        }
    }

    private fun drainLocked(): List<ManagedMigrationCopyReceipt> {
        if (pending.isEmpty()) return emptyList()
        val batch = pending.values.toList()
        pending.clear()
        return batch
    }

    private companion object {
        const val DEFAULT_BATCH_SIZE = 16
    }
}
