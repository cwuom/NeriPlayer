package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.task.DownloadTaskStore
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdmissionGateTest {
    @Test
    fun `open ticket is unavailable while clear owns the gate`() = runTest {
        val gate = DownloadAdmissionGate()
        val openTicket = requireNotNull(gate.openTicketOrNull())
        val clearToken = gate.beginClear()

        assertNull(gate.openTicketOrNull())
        assertFalse(gate.admit(openTicket) {})

        gate.runClear(clearToken) {}
        assertTrue(requireNotNull(gate.openTicketOrNull()) > openTicket)
    }

    @Test
    fun `clear does not wait for admitted work and rejects its stale ticket`() = runTest {
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

        // 清空只切换代次并执行自己的短阶段，不应等待旧 I/O
        cleared.await()
        assertFalse(oldRequest.isCompleted)
        release.complete(Unit)
        oldRequest.await()
        clear.await()

        assertTrue(cleared.isCompleted)
        assertFalse(gate.admit(staleTicket) {})
    }

    @Test
    fun `admitted suspend blocks do not serialize behind one another`() = runTest {
        val gate = DownloadAdmissionGate()
        val ticket = gate.ticket()
        val firstEntered = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = async {
            gate.admit(ticket) {
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()

        val second = async {
            gate.admit(ticket) {
                secondEntered.complete(Unit)
            }
        }
        secondEntered.await()
        assertTrue(second.await())
        assertFalse(first.isCompleted)

        releaseFirst.complete(Unit)
        assertTrue(first.await())
    }

    @Test
    fun `await idle waits for work admitted before clear`() = runTest {
        val gate = DownloadAdmissionGate()
        val ticket = gate.ticket()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val request = async {
            gate.admit(ticket) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()

        gate.beginClear()
        val idle = async { gate.awaitIdle() }
        assertFalse(idle.isCompleted)

        release.complete(Unit)
        request.await()
        idle.await()
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
    fun `failed clear release lets a later retry own the gate`() = runTest {
        val gate = DownloadAdmissionGate()
        val owner = gate.beginClear()
        val follower = gate.beginClear()
        val waiting = async { gate.awaitClear(follower) }

        assertTrue(gate.releaseFailedClear(owner))
        waiting.await()
        assertTrue(waiting.isCompleted)

        val retry = gate.beginClear()
        assertTrue(retry.ownsClear)
        assertTrue(retry.generation > owner.generation)
        gate.runClear(retry) {}
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
    fun `task presentation only clears after the fence is persisted`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()

        visibility.begin(token)
        assertTrue(visibility.isClearing.value)
        assertFalse(visibility.isTaskPresentationCleared.value)

        visibility.markFencePersisted(token)
        assertTrue(visibility.isTaskPresentationCleared.value)

        gate.runClear(token) {}
        visibility.finish(token)
        assertFalse(visibility.isClearing.value)
        assertFalse(visibility.isTaskPresentationCleared.value)
    }

    @Test
    fun `clear visibility exposes bounded phase progress and resets after finish`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()

        visibility.begin(token)
        assertEquals(0, visibility.progress.value?.percentage)
        visibility.markFencePersisted(token)
        visibility.update(
            token = token,
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            affectedItemCount = 701,
            completedItemCount = 8,
            totalItemCount = 20
        )
        assertEquals(50, visibility.progress.value?.percentage)
        assertEquals(60, visibility.progress.value?.displayPercentage)
        assertEquals(8, visibility.progress.value?.completedItemCount)
        assertEquals(20, visibility.progress.value?.totalItemCount)
        assertEquals(701, visibility.progress.value?.affectedItemCount)

        gate.runClear(token) {}
        visibility.finish(token)
        assertNull(visibility.progress.value)
    }

    @Test
    fun `clear visibility publishes the affected high water mark at begin`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()

        visibility.begin(
            token = token,
            affectedItemCount = 846,
            totalItemCount = 0
        )

        val progress = requireNotNull(visibility.progress.value)
        assertEquals(0, progress.displayPercentage)
        assertEquals(846, progress.affectedItemCount)
        assertEquals(0, progress.totalItemCount)
        assertEquals(0, progress.completedItemCount)
    }

    @Test
    fun `clear visibility never lowers the affected high water mark`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()

        visibility.begin(token, affectedItemCount = 846)
        visibility.update(
            token = token,
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            affectedItemCount = 12,
            totalItemCount = 12
        )

        assertEquals(846, visibility.progress.value?.affectedItemCount)
    }

    @Test
    fun `clear visibility keeps item watermark across a retry`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()

        visibility.begin(token)
        visibility.update(
            token = token,
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            affectedItemCount = 25,
            completedItemCount = 25,
            totalItemCount = 25
        )
        visibility.update(
            token = token,
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            affectedItemCount = 25,
            completedItemCount = 0,
            totalItemCount = 25
        )

        assertEquals(25, visibility.progress.value?.completedItemCount)
        assertEquals(25, visibility.progress.value?.totalItemCount)
    }

    @Test
    fun `complete provider scan may replace stale item watermark`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()

        visibility.begin(token)
        visibility.update(
            token = token,
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            affectedItemCount = 696,
            completedItemCount = 0,
            totalItemCount = 696
        )
        visibility.update(
            token = token,
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            affectedItemCount = 696,
            completedItemCount = 5,
            totalItemCount = 5,
            resetItemWatermark = true
        )

        assertEquals(5, visibility.progress.value?.completedItemCount)
        assertEquals(5, visibility.progress.value?.totalItemCount)
    }

    @Test
    fun `artifact progress keeps complete residual scan as retryable work`() {
        val visibility = DownloadClearVisibility()
        val checkedProgress = DownloadClearVisibility.ClearProgress(
            phase = DownloadClearVisibility.ClearPhase.CLEANING,
            completedSteps = 2,
            totalSteps = 4,
            affectedItemCount = 25,
            completedItemCount = 25,
            totalItemCount = 25,
            failedItemCount = 25
        )

        assertEquals(75, checkedProgress.displayPercentage)

        assertEquals(
            DownloadClearVisibility.ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = 25,
                failedItemCount = 25
            ),
            visibility.resolveArtifactProgress(
                artifactCount = 25,
                scanComplete = true
            )
        )
        assertEquals(
            DownloadClearVisibility.ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = 1,
                failedItemCount = 1
            ),
            visibility.resolveArtifactProgress(
                artifactCount = 0,
                scanComplete = false
            )
        )
        assertEquals(
            DownloadClearVisibility.ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = 1,
                failedItemCount = 1
            ),
            visibility.resolveArtifactProgress(
                artifactCount = 0,
                scanComplete = true,
                cleanupFailed = true
            )
        )
        assertEquals(
            DownloadClearVisibility.ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = 3,
                failedItemCount = 3
            ),
            visibility.resolveArtifactProgress(
                artifactCount = 3,
                scanComplete = true,
                cleanupFailed = true
            )
        )
        assertEquals(
            DownloadClearVisibility.ArtifactProgress(
                completedItemCount = 0,
                totalItemCount = 0,
                failedItemCount = 0
            ),
            visibility.resolveArtifactProgress(
                artifactCount = -4,
                scanComplete = true
            )
        )
    }

    @Test
    fun `clear visibility can restore persisted phase progress for the active generation`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val token = gate.beginClear()
        visibility.begin(token)

        visibility.restore(
            token = token,
            progress = DownloadClearVisibility.ClearProgress(
                phase = DownloadClearVisibility.ClearPhase.CLEANING,
                completedSteps = 2,
                totalSteps = 4,
                affectedItemCount = 1000,
                completedItemCount = 240,
                totalItemCount = 1000
            )
        )

        assertTrue(visibility.isTaskPresentationCleared.value)
        assertEquals(50, visibility.progress.value?.percentage)
        assertEquals(240, visibility.progress.value?.completedItemCount)
        assertEquals(1000, visibility.progress.value?.totalItemCount)
    }

    @Test
    fun `follower clear keeps a persisted task presentation hidden`() = runTest {
        val gate = DownloadAdmissionGate()
        val visibility = DownloadClearVisibility()
        val owner = gate.beginClear()
        visibility.begin(owner)
        visibility.markFencePersisted(owner)

        val follower = gate.beginClear()
        visibility.begin(follower)

        assertTrue(visibility.isTaskPresentationCleared.value)

        gate.runClear(owner) {}
        visibility.finish(owner)
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

    @Test
    fun `recovery captured before clear cannot recreate a task after one clear`() = runTest {
        val taskScope = CoroutineScope(SupervisorJob())
        try {
            val gate = DownloadAdmissionGate()
            val taskStore = DownloadTaskStore(
                scope = taskScope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val song = SongItem(
                id = 2L,
                name = "Recovery song",
                artist = "Artist",
                album = "Album",
                albumId = 2L,
                durationMs = 1_000L,
                coverUrl = null,
                mediaUri = "https://example.com/recovery"
            )
            taskStore.ensureDownloadTasks(listOf(song))

            // 模拟恢复先读取候选，再在真正写回前等待后台调度
            val capturedTicket = requireNotNull(gate.openTicketOrNull())
            val planRead = CompletableDeferred<Unit>()
            val continueRecovery = CompletableDeferred<Unit>()
            val recovery = async {
                planRead.complete(Unit)
                continueRecovery.await()
                gate.admit(capturedTicket) {
                    taskStore.ensureDownloadTasks(listOf(song))
                }
            }
            planRead.await()

            val clearToken = gate.beginClear()
            gate.runClear(clearToken) {
                taskStore.clearAllTasks()
            }
            continueRecovery.complete(Unit)

            assertFalse(recovery.await())
            assertTrue(taskStore.currentTasks().isEmpty())

            // 清空完成后，新请求仍应取得新票据并正常建卡
            val freshTicket = requireNotNull(gate.openTicketOrNull())
            assertTrue(
                gate.admit(freshTicket) {
                    taskStore.ensureDownloadTasks(listOf(song))
                }
            )
            assertEquals(1, taskStore.currentTasks().size)
        } finally {
            taskScope.cancel()
        }
    }
}
