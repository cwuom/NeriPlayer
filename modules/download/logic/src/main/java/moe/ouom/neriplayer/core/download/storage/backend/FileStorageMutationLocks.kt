package moe.ouom.neriplayer.core.download.storage.backend

import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object FileStorageMutationLocks {
    private const val STRIPE_COUNT = 64
    private const val BLOCKING_LOCK_TIMEOUT_NS = 2_000_000_000L
    private val locks = Array(STRIPE_COUNT) { Mutex() }

    fun forTarget(target: File): Mutex {
        return locks[stripeFor(target)]
    }

    suspend fun <T> withTargetLock(
        target: File,
        block: suspend () -> T
    ): T {
        return forTarget(target).withLock { block() }
    }

    suspend fun <T> withTargetLocks(
        first: File,
        second: File,
        block: suspend () -> T
    ): T {
        val firstStripe = stripeFor(first)
        val secondStripe = stripeFor(second)
        if (firstStripe == secondStripe) {
            return withTargetLock(first, block)
        }
        val (lower, higher) = if (firstStripe < secondStripe) {
            first to second
        } else {
            second to first
        }
        return withTargetLock(lower) {
            withTargetLock(higher, block)
        }
    }

    fun <T> withTargetLockBlocking(
        target: File,
        block: () -> T
    ): T {
        val lock = forTarget(target)
        val deadline = System.nanoTime() + BLOCKING_LOCK_TIMEOUT_NS
        var parkNanos = 1_000L
        while (!lock.tryLock()) {
            if (Thread.currentThread().isInterrupted) {
                throw InterruptedException("文件目标锁等待被中断: ${target.name}")
            }
            if (System.nanoTime() >= deadline) {
                throw IllegalStateException("文件目标锁等待超时: ${target.name}")
            }
            java.util.concurrent.locks.LockSupport.parkNanos(parkNanos)
            parkNanos = (parkNanos shl 1).coerceAtMost(1_000_000L)
        }
        return try {
            block()
        } finally {
            lock.unlock()
        }
    }

    fun <T> withTargetLocksBlocking(
        first: File,
        second: File,
        block: () -> T
    ): T {
        val firstStripe = stripeFor(first)
        val secondStripe = stripeFor(second)
        if (firstStripe == secondStripe) {
            return withTargetLockBlocking(first, block)
        }
        val (lower, higher) = if (firstStripe < secondStripe) {
            first to second
        } else {
            second to first
        }
        return withTargetLockBlocking(lower) {
            withTargetLockBlocking(higher, block)
        }
    }

    private fun stripeFor(target: File): Int {
        val key = runCatching { target.canonicalPath }
            .getOrElse { target.absolutePath }
        return Math.floorMod(key.hashCode(), STRIPE_COUNT)
    }

}
