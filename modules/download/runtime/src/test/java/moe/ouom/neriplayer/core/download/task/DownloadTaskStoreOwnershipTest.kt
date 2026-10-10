package moe.ouom.neriplayer.core.download.task

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadProgress
import moe.ouom.neriplayer.data.model.download.DownloadStage
import moe.ouom.neriplayer.data.model.download.DownloadStatus
import moe.ouom.neriplayer.data.model.download.DownloadTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskStoreOwnershipTest {

    @Test
    fun `clear without explicit owners hides every task and later owners join the active clear`() = withStore { store ->
        store.prepareDownloadTask(song(1L))
        store.prepareDownloadTask(song(2L))

        val token = store.beginClearPresentation()
        assertEquals(listOf(song(1L), song(2L)), token.visibleTasks.map(DownloadTask::song))
        assertTrue(store.currentTasks().isEmpty())

        store.prepareDownloadTask(song(3L))
        assertEquals(listOf(song(3L)), store.currentTasks().map(DownloadTask::song))
        assertSame(token, store.beginClearPresentation(listOf(" ${song(3L).stableKey()} ", "  ")))
        assertSame(token, store.beginClearPresentation())
        assertTrue(store.currentTasks().isEmpty())
        assertTrue(song(3L).stableKey() in token.blockedStableKeys)
        assertEquals(listOf(song(1L), song(2L)), token.visibleTasks.map(DownloadTask::song))
    }

    @Test
    fun `explicit clear owners only hide owned tasks until ownership grows`() = withStore { store ->
        store.prepareDownloadTask(song(1L))
        store.prepareDownloadTask(song(2L))

        val token = store.beginClearPresentation(listOf(song(1L).stableKey(), "  "))
        assertEquals(listOf(song(1L)), token.visibleTasks.map(DownloadTask::song))
        assertEquals(listOf(song(2L)), store.currentTasks().map(DownloadTask::song))

        assertTrue(store.addClearPresentationOwnership(token, listOf(song(2L).stableKey())))
        assertTrue(store.addClearPresentationOwnership(token, listOf("   ")))
        assertTrue(store.currentTasks().isEmpty())
        assertEquals(setOf(song(1L).stableKey(), song(2L).stableKey()), token.blockedStableKeys)

        assertTrue(store.finishClearPresentation(token))
        assertFalse(store.addClearPresentationOwnership(token, listOf(song(3L).stableKey())))
        val newer = store.beginClearPresentation(emptyList())
        assertFalse(store.addClearPresentationOwnership(token, listOf(song(3L).stableKey())))
        assertEquals(emptySet<String>(), newer.blockedStableKeys)
    }

    @Test
    fun `restoring progress requires a known restorable task of the same attempt`() = withStore { store ->
        val failedAttempt = requireNotNull(store.prepareDownloadTask(song(1L), DownloadStatus.QUEUED))
        assertTrue(store.updateTaskStatus(song(1L).stableKey(), DownloadStatus.FAILED, failedAttempt))
        val queuedAttempt = requireNotNull(store.prepareDownloadTask(song(2L), DownloadStatus.QUEUED))

        assertFalse(store.restoreProgress(progress(song(9L), attemptId = 1L, bytesRead = 10L)))
        assertFalse(store.restoreProgress(progress(song(1L), failedAttempt, bytesRead = 10L)))
        assertFalse(store.restoreProgress(progress(song(2L), queuedAttempt + 100L, bytesRead = 10L)))
        assertNull(store.findTask(song(1L).stableKey())?.progress)

        val restored = progress(song(2L), queuedAttempt, bytesRead = 10L)
        assertTrue(store.restoreProgress(restored))
        assertTrue(store.restoreProgress(restored))
        assertEquals(restored, store.findTask(song(2L).stableKey())?.progress)
    }

    @Test
    fun `batch restore only counts restorable tasks of the current attempt`() = withStore { store ->
        val attempts = (1L..4L).associateWith { id ->
            requireNotNull(store.prepareDownloadTask(song(id), DownloadStatus.QUEUED))
        }
        assertTrue(store.updateTaskStatus(song(2L).stableKey(), DownloadStatus.FAILED, attempts.getValue(2L)))
        val unchanged = progress(song(4L), attempts.getValue(4L), bytesRead = 30L)
        assertTrue(store.restoreProgress(unchanged))
        val restored = progress(song(1L), attempts.getValue(1L), bytesRead = 40L)

        assertEquals(0, store.restoreProgressBatch(emptyList()))
        assertEquals(
            2,
            store.restoreProgressBatch(
                listOf(
                    progress(song(9L), attemptId = 1L, bytesRead = 10L),
                    progress(song(2L), attempts.getValue(2L), bytesRead = 10L),
                    progress(song(3L), attempts.getValue(3L) + 100L, bytesRead = 10L),
                    unchanged,
                    restored
                )
            )
        )
        assertEquals(restored, store.findTask(song(1L).stableKey())?.progress)
        assertNull(store.findTask(song(2L).stableKey())?.progress)
        assertNull(store.findTask(song(3L).stableKey())?.progress)
        assertEquals(unchanged, store.findTask(song(4L).stableKey())?.progress)
    }

    @Test
    fun `obsolete waiting network tasks are dropped unless they are recovery candidates`() = withStore { store ->
        val songs = (1L..3L).map(::song)
        store.ensureDownloadTasks(
            songs,
            statusesBySongKey = mapOf(
                songs[0].stableKey() to DownloadStatus.WAITING_NETWORK,
                songs[1].stableKey() to DownloadStatus.WAITING_NETWORK
            )
        )

        store.removeObsoleteWaitingNetworkTasks(setOf(songs[1].stableKey()))

        assertEquals(listOf(songs[1], songs[2]), store.currentTasks().map(DownloadTask::song))
        assertEquals(
            listOf(DownloadStatus.WAITING_NETWORK, DownloadStatus.QUEUED),
            store.currentTasks().map(DownloadTask::status)
        )
    }

    @Test
    fun `removing a task requires a matching attempt when one is expected`() = withStore { store ->
        val attempt = requireNotNull(store.prepareDownloadTask(song(1L)))
        store.prepareDownloadTask(song(2L))

        store.removeDownloadTask("missing")
        store.removeDownloadTask(song(1L).stableKey(), attempt + 1L)
        assertEquals(listOf(song(1L), song(2L)), store.currentTasks().map(DownloadTask::song))

        store.removeDownloadTask(song(1L).stableKey(), attempt)
        store.removeDownloadTask(song(2L).stableKey())
        assertTrue(store.currentTasks().isEmpty())
    }

    @Test
    fun `only queued or downloading attempts are active`() = withStore { store ->
        val downloading = requireNotNull(store.prepareDownloadTask(song(1L)))
        store.prepareDownloadTask(song(2L), DownloadStatus.QUEUED)
        store.prepareDownloadTask(song(3L), DownloadStatus.WAITING_NETWORK)

        assertFalse(store.isDownloadAttemptActive("missing"))
        assertTrue(store.isDownloadAttemptActive(song(1L).stableKey()))
        assertTrue(store.isDownloadAttemptActive(song(1L).stableKey(), downloading))
        assertFalse(store.isDownloadAttemptActive(song(1L).stableKey(), downloading + 1L))
        assertTrue(store.isDownloadAttemptActive(song(2L).stableKey()))
        assertFalse(store.isDownloadAttemptActive(song(3L).stableKey()))

        assertTrue(store.updateTaskStatus(song(1L).stableKey(), DownloadStatus.COMPLETED))
        assertFalse(store.isDownloadAttemptActive(song(1L).stableKey()))
    }

    @Test
    fun `throttled progress still publishes stage total and finalizing changes`() =
        withStore(progressEmitIntervalNs = Long.MAX_VALUE) { store ->
            val attempt = requireNotNull(store.prepareDownloadTask(song(1L)))
            val key = song(1L).stableKey()

            assertTrue(store.updateProgress(progress(song(1L), attempt, bytesRead = 100L)))
            assertFalse(store.updateProgress(progress(song(1L), attempt, bytesRead = 200L)))
            assertEquals(100L, store.findTask(key)?.progress?.bytesRead)

            assertTrue(store.updateProgress(progress(song(1L), attempt, bytesRead = 200L, totalBytes = 2_000L)))
            assertEquals(2_000L, store.findTask(key)?.progress?.totalBytes)
            assertTrue(
                store.updateProgress(
                    progress(song(1L), attempt, bytesRead = 200L, totalBytes = 2_000L, stage = DownloadStage.FINALIZING)
                )
            )
            assertTrue(
                store.updateProgress(
                    progress(song(1L), attempt, bytesRead = 300L, totalBytes = 2_000L, stage = DownloadStage.FINALIZING)
                )
            )
            assertEquals(2_000L, store.findTask(key)?.progress?.bytesRead)
        }

    @Test
    fun `unthrottled progress publishes only visible changes`() = withStore(progressEmitIntervalNs = 0L) { store ->
        val attempt = requireNotNull(store.prepareDownloadTask(song(1L)))
        val key = song(1L).stableKey()

        assertTrue(store.updateProgress(progress(song(1L), attempt, bytesRead = 100L)))
        assertTrue(store.updateProgress(progress(song(1L), attempt, bytesRead = 200L)))
        assertTrue(store.updateProgress(progress(song(1L), attempt, bytesRead = 201L)))
        assertTrue(store.updateProgress(progress(song(1L), attempt, bytesRead = 201L, speed = 20L)))
        assertEquals(20L, store.findTask(key)?.progress?.speedBytesPerSec)

        val durableOnly = progress(song(1L), attempt, bytesRead = 201L, speed = 20L).copy(durableBytesRead = 100L)
        assertFalse(store.updateProgress(durableOnly))
        assertNull(store.findTask(key)?.progress?.durableBytesRead)
    }

    private fun withStore(
        progressEmitIntervalNs: Long = Long.MAX_VALUE,
        block: (DownloadTaskStore) -> Unit
    ) {
        val scope = CoroutineScope(SupervisorJob())
        try {
            block(DownloadTaskStore(scope, progressEmitIntervalNs))
        } finally {
            scope.cancel()
        }
    }

    private fun song(id: Long): SongItem {
        return SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 1L,
            durationMs = 180_000L,
            coverUrl = null,
            mediaUri = "https://example.com/$id"
        )
    }

    private fun progress(
        song: SongItem,
        attemptId: Long,
        bytesRead: Long,
        totalBytes: Long = 1_000L,
        speed: Long = 10L,
        stage: DownloadStage = DownloadStage.TRANSFERRING
    ): DownloadProgress {
        return DownloadProgress(
            songKey = song.stableKey(),
            songId = song.id,
            fileName = "song.mp3",
            bytesRead = bytesRead,
            totalBytes = totalBytes,
            speedBytesPerSec = speed,
            stage = stage,
            attemptId = attemptId
        )
    }
}
