package moe.ouom.neriplayer.core.download.storage.migration

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.ouom.neriplayer.core.download.ManagedDownloadStorage

/**
 * serializes migration progress ownership across replacement workers
 *
 * WorkManager can briefly overlap the old and replacement workers. Keeping the
 * owner next to the flow prevents a late callback from one worker from clearing
 * or publishing over the replacement worker's snapshot
 */
internal class ManagedDownloadMigrationProgressSession {
    private val lock = Any()
    private val _flow = MutableStateFlow<ManagedDownloadStorage.MigrationProgress?>(null)
    private var ownerWorkId: String? = null

    val flow: StateFlow<ManagedDownloadStorage.MigrationProgress?> = _flow.asStateFlow()

    /** claims the session only after the previous worker has released it */
    fun tryClaim(
        ownerWorkId: String,
        initialProgress: ManagedDownloadStorage.MigrationProgress?
    ): Boolean {
        val owner = normalizeOwner(ownerWorkId)
        synchronized(lock) {
            val currentOwner = this.ownerWorkId
            if (currentOwner != null && currentOwner != owner) return false
            this.ownerWorkId = owner
            _flow.value = initialProgress
            return true
        }
    }

    /** initializes a session without taking it from a newer worker */
    fun ensure(
        ownerWorkId: String,
        initialProgress: ManagedDownloadStorage.MigrationProgress?
    ): Boolean {
        val owner = normalizeOwner(ownerWorkId)
        synchronized(lock) {
            val currentOwner = this.ownerWorkId
            if (currentOwner != null && currentOwner != owner) return false
            this.ownerWorkId = owner
            _flow.value = initialProgress
            return true
        }
    }

    fun ensureLegacy(initialProgress: ManagedDownloadStorage.MigrationProgress?): Boolean {
        synchronized(lock) {
            if (ownerWorkId != null) return false
            _flow.value = initialProgress
            return true
        }
    }

    fun isOwner(ownerWorkId: String): Boolean {
        val owner = normalizeOwnerOrNull(ownerWorkId) ?: return false
        return synchronized(lock) { this.ownerWorkId == owner }
    }

    fun publish(
        ownerWorkId: String?,
        progress: ManagedDownloadStorage.MigrationProgress
    ): Boolean {
        val owner = normalizeOwnerOrNull(ownerWorkId)
        synchronized(lock) {
            if (this.ownerWorkId != owner) return false
            _flow.value = progress
            return true
        }
    }

    fun restoreIfIdle(progress: ManagedDownloadStorage.MigrationProgress): Boolean {
        synchronized(lock) {
            if (ownerWorkId != null) return false
            _flow.value = progress
            return true
        }
    }

    fun finish(ownerWorkId: String?): Boolean {
        val owner = normalizeOwnerOrNull(ownerWorkId)
        synchronized(lock) {
            if (this.ownerWorkId != owner) return false
            this.ownerWorkId = null
            _flow.value = null
            return true
        }
    }

    private fun normalizeOwner(ownerWorkId: String): String {
        return normalizeOwnerOrNull(ownerWorkId)
            ?: error("migration progress owner must not be blank")
    }

    private fun normalizeOwnerOrNull(ownerWorkId: String?): String? {
        return ownerWorkId?.trim()?.takeIf(String::isNotBlank)
    }
}
