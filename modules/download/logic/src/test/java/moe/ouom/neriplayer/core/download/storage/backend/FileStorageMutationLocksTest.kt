package moe.ouom.neriplayer.core.download.storage.backend

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileStorageMutationLocksTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `the same target is locked once for both roles`() {
        val target = File(temporaryFolder.root, "song.flac")

        val result = FileStorageMutationLocks.withTargetLocksBlocking(target, target) {
            FileStorageMutationLocks.forTarget(target).isLocked
        }

        assertTrue(result)
        assertFalse(FileStorageMutationLocks.forTarget(target).isLocked)
    }

    @Test
    fun `distinct targets hold both stripes in either argument order`() {
        val (first, second) = targetsOnDistinctStripes()

        listOf(first to second, second to first).forEach { (a, b) ->
            val held = FileStorageMutationLocks.withTargetLocksBlocking(a, b) {
                FileStorageMutationLocks.forTarget(a).isLocked to FileStorageMutationLocks.forTarget(b).isLocked
            }

            assertEquals(true to true, held)
            assertFalse(FileStorageMutationLocks.forTarget(a).isLocked)
            assertFalse(FileStorageMutationLocks.forTarget(b).isLocked)
        }
    }

    @Test
    fun `suspending locks cover shared and distinct stripes`() = runTest {
        val (first, second) = targetsOnDistinctStripes()

        listOf(first to first, first to second, second to first).forEach { (a, b) ->
            val held = FileStorageMutationLocks.withTargetLocks(a, b) {
                FileStorageMutationLocks.forTarget(a).isLocked && FileStorageMutationLocks.forTarget(b).isLocked
            }

            assertTrue(held)
            assertFalse(FileStorageMutationLocks.forTarget(a).isLocked)
            assertFalse(FileStorageMutationLocks.forTarget(b).isLocked)
        }
    }

    @Test
    fun `an interrupted waiter gives up without running the block`() {
        val target = File(temporaryFolder.root, "busy.flac")
        val lock = FileStorageMutationLocks.forTarget(target)
        assertTrue(lock.tryLock())
        var ran = false
        try {
            Thread.currentThread().interrupt()
            val error = assertThrows(InterruptedException::class.java) {
                FileStorageMutationLocks.withTargetLockBlocking(target) { ran = true }
            }
            assertTrue(error.message.orEmpty().endsWith(": busy.flac"))
        } finally {
            Thread.interrupted()
            lock.unlock()
        }
        assertFalse(ran)
        assertFalse(lock.isLocked)
    }

    private fun targetsOnDistinctStripes(): Pair<File, File> {
        val first = File(temporaryFolder.root, "song.flac")
        val second = generateSequence(0) { it + 1 }
            .map { File(temporaryFolder.root, "song-$it.npmeta.json") }
            .first { FileStorageMutationLocks.forTarget(it) !== FileStorageMutationLocks.forTarget(first) }
        assertNotSame(FileStorageMutationLocks.forTarget(first), FileStorageMutationLocks.forTarget(second))
        return first to second
    }
}
