package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.task.DownloadTaskStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdmissionGateTest {
    @Test
    fun `clear waits for admitted work and rejects its stale ticket`() = runTest {
        val gate = DownloadAdmissionGate()
        val staleTicket = gate.ticket()
        val admitted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val oldRequest = async {
            gate.admit(staleTicket) {
                admitted.complete(Unit)
                release.await()
            }
        }
        admitted.await()

        val clearToken = gate.beginClear()
        val cleared = CompletableDeferred<Unit>()
        val clear = async {
            gate.runClear(clearToken) {
                cleared.complete(Unit)
            }
        }

        assertFalse(cleared.isCompleted)
        release.complete(Unit)
        oldRequest.await()
        clear.await()

        assertTrue(cleared.isCompleted)
        assertFalse(gate.admit(staleTicket) {})
    }

    @Test
    fun `request created during clear waits and starts after cleanup`() = runTest {
        val gate = DownloadAdmissionGate()
        val clearToken = gate.beginClear()
        val currentTicket = gate.ticket()
        val releaseClear = CompletableDeferred<Unit>()
        val clear = async {
            gate.runClear(clearToken) {
                releaseClear.await()
            }
        }
        var started = false
        val request = async {
            gate.admit(currentTicket) {
                started = true
            }
        }

        assertFalse(started)
        releaseClear.complete(Unit)
        clear.await()

        assertTrue(request.await())
        assertTrue(started)
    }

    @Test
    fun `repeated clear shares the active completion`() = runTest {
        val gate = DownloadAdmissionGate()
        val owner = gate.beginClear()
        val follower = gate.beginClear()

        assertTrue(owner.ownsClear)
        assertFalse(follower.ownsClear)
        val waiting = async { gate.awaitClear(follower) }
        assertFalse(waiting.isCompleted)

        gate.runClear(owner) {}
        waiting.await()
        assertTrue(waiting.isCompleted)
    }

    @Test
    fun `finishing an older clear cannot hide a newer clear`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val first = gate.beginClear()
        visibility.begin(first)
        gate.runClear(first) {}

        val second = gate.beginClear()
        visibility.begin(second)
        visibility.finish(first)

        assertTrue(visibility.isClearing.value)
        gate.runClear(second) {}
        visibility.finish(second)
        assertFalse(visibility.isClearing.value)
    }

    @Test
    fun `clear rejects stale task creation and admits a request created afterward`() = runTest {
        val taskScope = CoroutineScope(SupervisorJob())
        try {
            val gate = DownloadAdmissionGate()
            val taskStore = DownloadTaskStore(
                scope = taskScope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val song = SongItem(
                id = 1L,
                name = "Song",
                artist = "Artist",
                album = "Album",
                albumId = 1L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/song"
            )
            val staleTicket = gate.ticket()
            val clearToken = gate.beginClear()

            assertFalse(
                gate.admit(staleTicket) {
                    taskStore.ensureDownloadTasks(listOf(song))
                }
            )
            assertTrue(taskStore.currentTasks().isEmpty())

            gate.runClear(clearToken) {}
            assertTrue(
                gate.admit(gate.ticket()) {
                    taskStore.ensureDownloadTasks(listOf(song))
                }
            )
            assertTrue(taskStore.currentTasks().isNotEmpty())
        } finally {
            taskScope.cancel()
        }
    }
}
