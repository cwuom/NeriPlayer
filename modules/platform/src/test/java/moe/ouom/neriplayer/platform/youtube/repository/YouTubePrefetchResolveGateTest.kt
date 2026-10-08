package moe.ouom.neriplayer.platform.youtube.repository

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubePrefetchResolveGateTest {

    @Test
    fun `promotion releases a waiter blocked by a full gate`() = runTest {
        val gate = YouTubePrefetchResolveGate(permits = 1)
        val holderInside = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()
        val holderPromotion = CompletableDeferred<Unit>()
        val waiterPromotion = CompletableDeferred<Unit>()

        val holder = async {
            gate.withPrefetchSlot(holderPromotion) {
                holderInside.complete(Unit)
                releaseHolder.await()
                "holder"
            }
        }
        holderInside.await()

        val waiter = async {
            gate.withPrefetchSlot(waiterPromotion) { "waiter" }
        }
        assertFalse(waiter.isCompleted)

        // 名额还被占着, 只有提升能让这条解析立刻开跑
        waiterPromotion.complete(Unit)
        assertEquals("waiter", withTimeout(5_000.milliseconds) { waiter.await() })
        assertFalse(holder.isCompleted)

        releaseHolder.complete(Unit)
        assertEquals("holder", holder.await())
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun `an already promoted request never touches the gate`() = runTest {
        val gate = YouTubePrefetchResolveGate(permits = 1)
        val holderInside = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val holder = async {
            gate.withPrefetchSlot(CompletableDeferred()) {
                holderInside.complete(Unit)
                releaseHolder.await()
            }
        }
        holderInside.await()

        val promoted = CompletableDeferred<Unit>().apply { complete(Unit) }
        assertEquals("now", withTimeout(5_000.milliseconds) { gate.withPrefetchSlot(promoted) { "now" } })

        releaseHolder.complete(Unit)
        holder.await()
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun `unpromoted waiters still queue behind the permit holder`() = runTest {
        val gate = YouTubePrefetchResolveGate(permits = 1)
        val order = mutableListOf<String>()
        val holderInside = CompletableDeferred<Unit>()
        val releaseHolder = CompletableDeferred<Unit>()

        val holder = async {
            gate.withPrefetchSlot(CompletableDeferred()) {
                order.add("holder")
                holderInside.complete(Unit)
                releaseHolder.await()
            }
        }
        holderInside.await()

        val waiter = async {
            gate.withPrefetchSlot(CompletableDeferred()) { order.add("waiter") }
        }
        assertFalse(waiter.isCompleted)

        releaseHolder.complete(Unit)
        holder.await()
        withTimeout(5_000.milliseconds) { waiter.await() }

        assertEquals(listOf("holder", "waiter"), order.toList())
        assertEquals(1, gate.availablePermitsForTest())
    }

    @Test
    fun `cancelling the caller after the permit is granted but before hand off returns the permit`() =
        runTest(StandardTestDispatcher()) {
            val gate = YouTubePrefetchResolveGate(permits = 1)
            var bodyRan = false
            val caller = launch {
                gate.withPrefetchSlot(CompletableDeferred()) { bodyRan = true }
            }
            // 任务按入队顺序执行: caller 挂起等待交接后, yield 让取消排到名额获取之后、caller 恢复之前
            launch {
                yield()
                caller.cancel()
            }
            advanceUntilIdle()

            assertTrue(caller.isCancelled)
            assertFalse(bodyRan)
            assertEquals(1, gate.availablePermitsForTest())
        }

    @Test
    fun `permit is handed back when the body throws`() = runTest {
        val gate = YouTubePrefetchResolveGate(permits = 1)
        val failed = runCatching {
            gate.withPrefetchSlot(CompletableDeferred()) { error("boom") }
        }
        assertTrue(failed.isFailure)
        assertEquals(1, gate.availablePermitsForTest())
        assertEquals("recovered", gate.withPrefetchSlot(CompletableDeferred()) { "recovered" })
    }
}
