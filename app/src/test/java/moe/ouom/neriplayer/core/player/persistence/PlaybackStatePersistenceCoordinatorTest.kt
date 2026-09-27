package moe.ouom.neriplayer.core.player.persistence

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackStatePersistenceCoordinatorTest {
    @Test
    fun `debounce writes only the newest captured value`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<Int>()
        val saved = mutableListOf<Int>()
        var value = 1
        val first = checkNotNull(coordinator.prepare { value })
        coordinator.schedule(this, first, 100L) { saved.add(it) }
        advanceTimeBy(50.milliseconds)
        value = 2
        val second = checkNotNull(coordinator.prepare { value })
        coordinator.schedule(this, second, 100L) { saved.add(it) }
        value = 3
        advanceUntilIdle()

        assertEquals(listOf(2), saved)
    }

    @Test
    fun `an old pause request delayed by other work cannot overwrite a newer play`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<String>()
        val saved = mutableListOf<String>()
        val paused = checkNotNull(coordinator.prepare { "paused" })
        val playing = checkNotNull(coordinator.prepare { "playing" })

        assertTrue(coordinator.persist(playing) { saved.add(it) })
        assertFalse(coordinator.persist(paused) { saved.add(it) })
        assertEquals(listOf("playing"), saved)
    }

    @Test
    fun `immediate save replaces a pending delayed save`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<String>()
        val saved = mutableListOf<String>()
        val delayed = checkNotNull(coordinator.prepare { "old" })
        coordinator.schedule(this, delayed, 100L) { saved.add(it) }
        runCurrent()
        val immediate = checkNotNull(coordinator.prepare { "new" })

        assertTrue(coordinator.persist(immediate) { saved.add(it) })
        advanceUntilIdle()
        assertEquals(listOf("new"), saved)
    }

    @Test
    fun `writes stay serial and a superseded waiting writer never runs`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<String>()
        val saved = mutableListOf<String>()
        val releaseFirst = CompletableDeferred<Unit>()
        var activeWrites = 0
        var maxActiveWrites = 0
        val first = checkNotNull(coordinator.prepare { "first" })
        val firstSave = async {
            coordinator.persist(first) {
                activeWrites++
                maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
                releaseFirst.await()
                saved.add(it)
                activeWrites--
            }
        }
        runCurrent()
        val second = checkNotNull(coordinator.prepare { "second" })
        val secondSave = async { coordinator.persist(second) { saved.add(it) } }
        runCurrent()
        val last = checkNotNull(coordinator.prepare { "last" })
        val lastSave = async {
            coordinator.persist(last) {
                activeWrites++
                maxActiveWrites = maxOf(maxActiveWrites, activeWrites)
                saved.add(it)
                activeWrites--
            }
        }
        runCurrent()
        assertEquals(emptyList<String>(), saved)
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertFalse(firstSave.await())
        assertFalse(secondSave.await())
        assertTrue(lastSave.await())
        assertEquals(listOf("first", "last"), saved)
        assertEquals(1, maxActiveWrites)
    }

    @Test
    fun `old cleanup cannot cancel the next scheduled request`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<String>()
        val releaseOld = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val old = checkNotNull(coordinator.prepare { "old" })
        coordinator.schedule(this, old, 0L) {
            withContext(NonCancellable) { releaseOld.await() }
        }
        runCurrent()
        val next = checkNotNull(coordinator.prepare { "next" })
        coordinator.schedule(this, next, 100L) { saved.add(it) }
        releaseOld.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertEquals(listOf("next"), saved)
    }

    @Test
    fun `release rejects requests and reopening cannot revive an old request`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<Int>()
        val saved = mutableListOf<Int>()
        val old = checkNotNull(coordinator.prepare { 1 })
        coordinator.schedule(this, old, 100L) { saved.add(it) }
        coordinator.close()
        assertNull(coordinator.prepare { 2 })
        coordinator.reopen()
        assertFalse(coordinator.persist(old) { saved.add(it) })
        val next = checkNotNull(coordinator.prepare { 3 })
        assertTrue(coordinator.persist(next) { saved.add(it) })
        advanceUntilIdle()

        assertEquals(listOf(3), saved)
    }

    @Test
    fun `closing before IO starts cancels a direct save across reopening`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<Int>()
        val ioDispatcher = PausedDispatcher()
        val saved = mutableListOf<Int>()
        val old = checkNotNull(coordinator.prepare { 1 })
        val saving = async {
            coordinator.persist(old) { value ->
                withContext(ioDispatcher) { saved.add(value) }
            }
        }
        runCurrent()

        coordinator.close()
        coordinator.reopen()
        ioDispatcher.runPending()
        runCurrent()

        assertTrue(saving.isCancelled)
        assertTrue(coordinator.persist(checkNotNull(coordinator.prepare { 2 })) { saved.add(it) })
        assertEquals(listOf(2), saved)
    }

    @Test
    fun `reopening also cancels a direct save already waiting on storage`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<Int>()
        val releaseStorage = CompletableDeferred<Unit>()
        val saved = mutableListOf<Int>()
        val old = checkNotNull(coordinator.prepare { 1 })
        val saving = async {
            coordinator.persist(old) {
                releaseStorage.await()
                saved.add(it)
            }
        }
        runCurrent()

        coordinator.reopen()
        releaseStorage.complete(Unit)
        runCurrent()

        assertTrue(saving.isCancelled)
        assertTrue(coordinator.persist(checkNotNull(coordinator.prepare { 2 })) { saved.add(it) })
        assertEquals(listOf(2), saved)
    }

    @Test
    fun `write failure is propagated and a later request can still save`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<Int>()
        val failure = IOException("disk unavailable")
        val first = checkNotNull(coordinator.prepare { 1 })
        val thrown = try {
            coordinator.persist(first) { throw failure }
            null
        } catch (error: IOException) {
            error
        }
        assertEquals(failure.message, thrown?.message)
        assertSame(failure, generateSequence<Throwable>(thrown) { it.cause }.lastOrNull())
        assertTrue(coordinator.persist(checkNotNull(coordinator.prepare { 2 })) {})
    }

    @Test
    fun `cancelling the writer releases the next request`() = runTest {
        val coordinator = PlaybackStatePersistenceCoordinator<Int>()
        val first = checkNotNull(coordinator.prepare { 1 })
        val saving = launch { coordinator.persist(first) { awaitCancellation() } }
        runCurrent()
        saving.cancelAndJoin()
        val saved = mutableListOf<Int>()

        assertTrue(coordinator.persist(checkNotNull(coordinator.prepare { 2 })) { saved.add(it) })
        assertEquals(listOf(2), saved)
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }

        fun runPending() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
