package moe.ouom.neriplayer.data.sync

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object SyncCoordinator {
    private val lock = Mutex()

    fun tryLock(): Boolean = lock.tryLock()

    fun unlock() {
        lock.unlock()
    }

    suspend fun <T> withExclusive(block: suspend () -> T): T {
        return lock.withLock {
            block()
        }
    }
}
