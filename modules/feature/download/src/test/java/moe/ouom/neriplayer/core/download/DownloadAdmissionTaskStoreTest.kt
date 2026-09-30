package moe.ouom.neriplayer.core.download

import moe.ouom.neriplayer.core.download.admission.DownloadAdmissionGate
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
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadAdmissionTaskStoreTest {
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
