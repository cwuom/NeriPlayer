package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.core.player.download.AudioDownloadManager
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskStoreTest {

    @Test
    fun `active batch admission remains visible before task rows are hydrated`() {
        val summary = stabilizeDownloadTaskSummary(
            taskSummary = DownloadTaskSummary(),
            isSingleDownloading = false,
            hasActiveBatchJobs = true
        )

        assertEquals(0, summary.pendingTaskCount)
        assertFalse(summary.hasPendingTasks)
        assertTrue(summary.hasActiveOperations)
        assertTrue(summary.hasDownloadManagerEntry)
    }

    @Test
    fun `active transfer flag remains true until every concurrent transfer ends`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )

            store.beginDownloadTransfer()
            store.beginDownloadTransfer()
            assertTrue(store.isSingleDownloading)

            store.endDownloadTransfer()
            assertTrue(store.isSingleDownloading)

            store.endDownloadTransfer()
            assertFalse(store.isSingleDownloading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale progress cannot throttle the first event of the active attempt`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(1L)
            val activeAttemptId = store.prepareDownloadTask(downloadSong)
                ?: error("download task was not prepared")
            val currentProgress = progress(
                song = downloadSong,
                attemptId = activeAttemptId,
                bytesRead = 42L
            )

            assertFalse(
                store.updateProgress(
                    progress(
                        song = downloadSong,
                        attemptId = activeAttemptId + 1L,
                        bytesRead = 42L
                    )
                )
            )
            assertTrue(store.updateProgress(currentProgress))
            assertEquals(
                currentProgress,
                store.findTask(downloadSong.stableKey())?.progress
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `progress received before transfer registration is retained`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(2L)
            val attemptId = store.prepareDownloadTask(
                downloadSong,
                status = DownloadStatus.QUEUED
            ) ?: error("download task was not prepared")
            val queuedProgress = progress(
                song = downloadSong,
                attemptId = attemptId,
                bytesRead = 256L
            )

            assertTrue(store.updateProgress(queuedProgress))
            store.registerActiveDownloadTask(downloadSong, attemptId)

            val task = requireNotNull(store.findTask(downloadSong.stableKey()))
            assertEquals(DownloadStatus.DOWNLOADING, task.status)
            assertEquals(queuedProgress, task.progress)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `network pause retains verified partial progress for recovery`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(1L)
            val attemptId = store.prepareDownloadTask(downloadSong)
                ?: error("download task was not prepared")
            val partialProgress = progress(
                song = downloadSong,
                attemptId = attemptId,
                bytesRead = 42L
            )

            assertTrue(store.updateProgress(partialProgress))
            store.updateTaskStatus(
                songKey = downloadSong.stableKey(),
                status = DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = attemptId
            )

            assertEquals(DownloadStatus.WAITING_NETWORK, store.findTask(downloadSong.stableKey())?.status)
            assertEquals(partialProgress, store.findTask(downloadSong.stableKey())?.progress)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `durable progress restores before transfer starts and rejects stale attempt`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(99L)
            val attemptId = store.ensureDownloadTasks(
                songs = listOf(downloadSong),
                status = DownloadStatus.WAITING_NETWORK,
                durableAttemptIds = mapOf(downloadSong.stableKey() to 77L)
            ).getValue(downloadSong.stableKey())
            assertEquals(77L, attemptId)

            val checkpoint = progress(
                song = downloadSong,
                attemptId = attemptId,
                bytesRead = 512L
            )
            assertTrue(store.restoreProgress(checkpoint))
            assertEquals(checkpoint, store.findTask(downloadSong.stableKey())?.progress)
            assertFalse(
                store.restoreProgress(
                    progress(
                        song = downloadSong,
                        attemptId = attemptId + 1L,
                        bytesRead = 900L
                    )
                )
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `wifi recovery keeps restored progress when it resumes the same durable attempt`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(100L)
            val songKey = downloadSong.stableKey()
            val attemptId = store.ensureDownloadTasks(
                songs = listOf(downloadSong),
                status = DownloadStatus.WAITING_NETWORK,
                durableAttemptIds = mapOf(songKey to 7_777L)
            ).getValue(songKey)
            val checkpoint = progress(
                song = downloadSong,
                attemptId = attemptId,
                bytesRead = 4_096L
            )
            assertTrue(store.restoreProgress(checkpoint))

            val resumedAttemptId = store.ensureDownloadTasks(
                songs = listOf(downloadSong),
                status = DownloadStatus.QUEUED,
                durableAttemptIds = mapOf(songKey to attemptId)
            ).getValue(songKey)

            assertEquals(attemptId, resumedAttemptId)
            assertEquals(DownloadStatus.QUEUED, store.findTask(songKey)?.status)
            assertEquals(checkpoint, store.findTask(songKey)?.progress)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `batch restore updates many queued tasks with one logical snapshot`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val songs = (1L..256L).map(::song)
            val attemptIds = store.ensureDownloadTasks(
                songs = songs,
                status = DownloadStatus.QUEUED,
                durableAttemptIds = songs.associate { it.stableKey() to it.id + 10_000L }
            )

            val restored = store.restoreProgressBatch(
                songs.map { downloadSong ->
                    progress(
                        song = downloadSong,
                        attemptId = attemptIds.getValue(downloadSong.stableKey()),
                        bytesRead = downloadSong.id
                    )
                }
            )

            assertEquals(songs.size, restored)
            assertEquals(
                songs.map { it.id },
                store.currentTasks().map { task -> task.progress?.bytesRead }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `large active batch keeps per song progress isolated`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val songs = (1L..512L).map(::song)
            val attemptIds = store.prepareDownloadTasks(
                songs = songs,
                status = DownloadStatus.DOWNLOADING
            )

            songs.forEachIndexed { index, downloadSong ->
                val bytesRead = (index + 1).toLong()
                assertTrue(
                    store.updateProgress(
                        progress(
                            song = downloadSong,
                            attemptId = attemptIds.getValue(downloadSong.stableKey()),
                            bytesRead = bytesRead
                        )
                    )
                )
            }

            assertEquals(512, store.currentTasks().size)
            songs.forEachIndexed { index, downloadSong ->
                assertEquals(
                    (index + 1).toLong(),
                    store.findTask(downloadSong.stableKey())?.progress?.bytesRead
                )
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `prepareDownloadTasks prepares large batches with stable dedupe`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val songs = (1..832).map { index -> song(index.toLong()) }

            val attemptIds = store.prepareDownloadTasks(
                songs = songs + songs.first(),
                status = DownloadStatus.QUEUED
            )

            assertEquals(832, attemptIds.size)
            assertEquals(832, store.currentTasks().size)
            assertEquals(832, attemptIds.values.toSet().size)
            assertTrue(store.currentTasks().all { task -> task.status == DownloadStatus.QUEUED })

            val repeatedAttemptIds = store.prepareDownloadTasks(
                songs = songs,
                status = DownloadStatus.DOWNLOADING
            )

            assertTrue(repeatedAttemptIds.isEmpty())
            assertEquals(832, store.currentTasks().size)
            assertTrue(store.currentTasks().all { task -> task.status == DownloadStatus.QUEUED })
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `prepareDownloadTasks keeps active tasks and replaces retryable tasks`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val queuedSong = song(1L, "Queued")
            val downloadingSong = song(2L, "Downloading")
            val waitingSong = song(3L, "Waiting")
            val failedSong = song(4L, "Failed")
            val cancelledSong = song(5L, "Cancelled")
            val completedSong = song(6L, "Completed")
            val songs = listOf(
                queuedSong,
                downloadingSong,
                waitingSong,
                failedSong,
                cancelledSong,
                completedSong
            )
            val initialAttemptIds = store.prepareDownloadTasks(songs, DownloadStatus.QUEUED)

            store.updateTaskStatus(
                downloadingSong.stableKey(),
                DownloadStatus.DOWNLOADING,
                expectedAttemptId = initialAttemptIds.getValue(downloadingSong.stableKey())
            )
            store.updateTaskStatus(
                waitingSong.stableKey(),
                DownloadStatus.WAITING_NETWORK,
                expectedAttemptId = initialAttemptIds.getValue(waitingSong.stableKey())
            )
            store.updateTaskStatus(
                failedSong.stableKey(),
                DownloadStatus.FAILED,
                expectedAttemptId = initialAttemptIds.getValue(failedSong.stableKey())
            )
            store.updateTaskStatus(
                cancelledSong.stableKey(),
                DownloadStatus.CANCELLED,
                expectedAttemptId = initialAttemptIds.getValue(cancelledSong.stableKey())
            )
            store.updateTaskStatus(
                completedSong.stableKey(),
                DownloadStatus.COMPLETED,
                expectedAttemptId = initialAttemptIds.getValue(completedSong.stableKey())
            )

            val retryAttemptIds = store.prepareDownloadTasks(songs, DownloadStatus.QUEUED)

            assertFalse(retryAttemptIds.containsKey(queuedSong.stableKey()))
            assertFalse(retryAttemptIds.containsKey(downloadingSong.stableKey()))
            listOf(waitingSong, failedSong, cancelledSong, completedSong).forEach { retryableSong ->
                val songKey = retryableSong.stableKey()
                assertTrue(retryAttemptIds.containsKey(songKey))
                assertNotEquals(initialAttemptIds.getValue(songKey), retryAttemptIds.getValue(songKey))
                assertEquals(DownloadStatus.QUEUED, store.findTask(songKey)?.status)
            }
            assertEquals(
                DownloadStatus.DOWNLOADING,
                store.findTask(downloadingSong.stableKey())?.status
            )
            assertEquals(
                initialAttemptIds.getValue(queuedSong.stableKey()),
                store.findTask(queuedSong.stableKey())?.attemptId
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `prepareDownloadTasks can replace stale active tasks during recovery`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val songs = (1..225).map { index -> song(index.toLong()) }
            val staleAttemptIds = store.prepareDownloadTasks(
                songs = songs,
                status = DownloadStatus.QUEUED
            )

            val recoveryAttemptIds = store.prepareDownloadTasks(
                songs = songs,
                status = DownloadStatus.QUEUED,
                replaceExistingActiveTasks = true
            )

            assertEquals(225, recoveryAttemptIds.size)
            assertEquals(225, store.currentTasks().size)
            assertTrue(store.currentTasks().all { task -> task.status == DownloadStatus.QUEUED })
            songs.forEach { recoverySong ->
                val songKey = recoverySong.stableKey()
                assertNotEquals(
                    staleAttemptIds.getValue(songKey),
                    recoveryAttemptIds.getValue(songKey)
                )
                assertEquals(recoveryAttemptIds.getValue(songKey), store.findTask(songKey)?.attemptId)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `ensureDownloadTasks restores durable attempts and keeps active attempts`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val activeSong = song(1L, "Active")
            val restoredSong = song(2L, "Restored")
            val activeAttemptId = store.prepareDownloadTask(
                song = activeSong,
                status = DownloadStatus.QUEUED
            ) ?: error("active attempt was not prepared")

            val ensured = store.ensureDownloadTasks(
                songs = listOf(activeSong, restoredSong),
                status = DownloadStatus.QUEUED,
                durableAttemptIds = mapOf(
                    activeSong.stableKey() to 99L,
                    restoredSong.stableKey() to 100L
                )
            )

            assertEquals(activeAttemptId, ensured.getValue(activeSong.stableKey()))
            assertEquals(100L, ensured.getValue(restoredSong.stableKey()))
            assertEquals(100L, store.findTask(restoredSong.stableKey())?.attemptId)
            val nextSong = song(3L, "Next")
            val nextAttemptId = store.prepareDownloadTask(nextSong)
            assertTrue(nextAttemptId != null && nextAttemptId > 100L)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `removeDownloadTasks only removes matching attempts`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val firstSong = song(1L, "First")
            val secondSong = song(2L, "Second")
            val thirdSong = song(3L, "Third")
            val attemptIds = store.prepareDownloadTasks(
                songs = listOf(firstSong, secondSong, thirdSong),
                status = DownloadStatus.QUEUED
            )

            store.removeDownloadTasks(
                mapOf(
                    firstSong.stableKey() to attemptIds.getValue(firstSong.stableKey()),
                    secondSong.stableKey() to attemptIds.getValue(secondSong.stableKey()) + 1L
                )
            )

            assertEquals(
                listOf(secondSong.stableKey(), thirdSong.stableKey()),
                store.currentTasks().map { task -> task.song.stableKey() }
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clearAllTasks removes every task status`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val songs = (1L..6L).map { id -> song(id) }
            val attemptIds = store.prepareDownloadTasks(songs, DownloadStatus.QUEUED)
            val statuses = listOf(
                DownloadStatus.QUEUED,
                DownloadStatus.DOWNLOADING,
                DownloadStatus.WAITING_NETWORK,
                DownloadStatus.FAILED,
                DownloadStatus.CANCELLED,
                DownloadStatus.COMPLETED
            )

            songs.zip(statuses).forEach { (song, status) ->
                store.updateTaskStatus(
                    song.stableKey(),
                    status,
                    expectedAttemptId = attemptIds.getValue(song.stableKey())
                )
            }

            store.clearAllTasks()

            assertTrue(store.currentTasks().isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `clear presentation gate hides snapshot and rejects late task mutations`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(7L)
            val attemptId = store.prepareDownloadTask(downloadSong)
                ?: error("download task was not prepared")
            val visibleTask = requireNotNull(store.findTask(downloadSong.stableKey()))
            val clearToken = store.beginClearPresentation()

            assertEquals(listOf(visibleTask), clearToken.visibleTasks)
            assertTrue(store.isClearPresentationActive.value)
            assertTrue(store.currentTasks().isEmpty())
            assertTrue(store.currentClearPresentationToken() === clearToken)
            assertTrue(store.prepareDownloadTask(downloadSong) == null)
            assertTrue(store.prepareDownloadTasks(listOf(downloadSong)).isEmpty())
            assertTrue(store.ensureDownloadTasks(listOf(downloadSong)).isEmpty())
            assertFalse(store.updateProgress(progress(downloadSong, attemptId, 32L)))
            assertFalse(store.restoreProgress(progress(downloadSong, attemptId, 64L)))
            assertEquals(
                0,
                store.restoreProgressBatch(listOf(progress(downloadSong, attemptId, 96L)))
            )
            assertFalse(
                store.updateTaskStatus(
                    songKey = downloadSong.stableKey(),
                    status = DownloadStatus.QUEUED,
                    expectedAttemptId = attemptId
                )
            )
            store.registerActiveDownloadTask(downloadSong, attemptId)
            assertTrue(store.currentTasks().isEmpty())

            assertTrue(store.finishClearPresentation(clearToken))
            assertTrue(store.currentClearPresentationToken() == null)
            assertFalse(store.isClearPresentationActive.value)
            assertTrue(store.prepareDownloadTask(downloadSong) != null)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `stale clear presentation token cannot release a newer clear`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val firstToken = store.beginClearPresentation()
            assertTrue(store.finishClearPresentation(firstToken))
            val secondToken = store.beginClearPresentation()

            assertFalse(store.finishClearPresentation(firstToken))
            assertTrue(store.currentClearPresentationToken() === secondToken)
            assertTrue(store.prepareDownloadTask(song(8L)) == null)
            assertFalse(store.finishClearPresentation(secondToken) { false })
            assertTrue(store.currentClearPresentationToken() === secondToken)
            assertTrue(store.finishClearPresentation(secondToken))
            assertTrue(store.prepareDownloadTask(song(8L)) != null)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `failed task remains a cancellation candidate until durable retry is cancelled`() {
        val failedTask = DownloadTask(
            song = song(91L),
            progress = null,
            status = DownloadStatus.FAILED,
            attemptId = 7L
        )
        val completedTask = failedTask.copy(status = DownloadStatus.COMPLETED)
        val cancelledTask = failedTask.copy(status = DownloadStatus.CANCELLED)

        assertTrue(isDownloadTaskCancellationCandidate(failedTask))
        assertFalse(isDownloadTaskCancellationCandidate(completedTask))
        assertFalse(isDownloadTaskCancellationCandidate(cancelledTask))
    }

    @Test
    fun `same attempt retains its progress across a retry and worker reactivation`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(1L)
            val attemptId = store.prepareDownloadTask(downloadSong)
                ?: error("download task was not prepared")

            assertTrue(
                store.updateProgress(
                    progress(downloadSong, attemptId, bytesRead = 800L).copy(totalBytes = 0L)
                )
            )
            assertTrue(
                store.updateProgress(
                    progress(
                        song = downloadSong,
                        attemptId = attemptId,
                        bytesRead = 200L,
                        stage = AudioDownloadManager.DownloadStage.WAITING_RETRY
                    ).copy(speedBytesPerSec = 0L)
                )
            )
            val waitingProgress = requireNotNull(store.findTask(downloadSong.stableKey())?.progress)
            assertEquals(800L, waitingProgress.bytesRead)
            assertEquals(1_000L, waitingProgress.totalBytes)
            assertEquals(0L, waitingProgress.speedBytesPerSec)
            assertEquals(AudioDownloadManager.DownloadStage.WAITING_RETRY, waitingProgress.stage)

            store.registerActiveDownloadTask(downloadSong, attemptId)
            assertEquals(800L, store.findTask(downloadSong.stableKey())?.progress?.bytesRead)
            assertTrue(
                store.updateProgress(
                    progress(
                        song = downloadSong,
                        attemptId = attemptId,
                        bytesRead = 300L
                    )
                )
            )
            val restartedProgress = requireNotNull(store.findTask(downloadSong.stableKey())?.progress)
            assertEquals(800L, restartedProgress.bytesRead)
            assertEquals(1_000L, restartedProgress.totalBytes)
            assertEquals(AudioDownloadManager.DownloadStage.TRANSFERRING, restartedProgress.stage)

            assertFalse(
                store.updateProgress(
                    progress(downloadSong, attemptId, bytesRead = 900L).copy(attemptId = null)
                )
            )
            assertEquals(800L, store.findTask(downloadSong.stableKey())?.progress?.bytesRead)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `new attempt may restart from zero after a previous high water mark`() {
        val scope = CoroutineScope(SupervisorJob())
        try {
            val store = DownloadTaskStore(
                scope = scope,
                progressEmitIntervalNs = Long.MAX_VALUE
            )
            val downloadSong = song(2L)
            val firstAttemptId = store.prepareDownloadTask(downloadSong)
                ?: error("download task was not prepared")
            assertTrue(store.updateProgress(progress(downloadSong, firstAttemptId, bytesRead = 800L)))
            assertTrue(
                store.updateTaskStatus(
                    songKey = downloadSong.stableKey(),
                    status = DownloadStatus.CANCELLED,
                    expectedAttemptId = firstAttemptId
                )
            )

            val secondAttemptId = store.prepareDownloadTask(downloadSong)
                ?: error("retry task was not prepared")
            assertNotEquals(firstAttemptId, secondAttemptId)
            assertTrue(store.updateProgress(progress(downloadSong, secondAttemptId, bytesRead = 0L)))

            val restartedProgress = requireNotNull(store.findTask(downloadSong.stableKey())?.progress)
            assertEquals(secondAttemptId, restartedProgress.attemptId)
            assertEquals(0L, restartedProgress.bytesRead)
        } finally {
            scope.cancel()
        }
    }

    private fun song(
        id: Long,
        name: String = "Song $id"
    ): SongItem {
        return SongItem(
            id = id,
            name = name,
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
        stage: AudioDownloadManager.DownloadStage =
            AudioDownloadManager.DownloadStage.TRANSFERRING
    ): AudioDownloadManager.DownloadProgress {
        return AudioDownloadManager.DownloadProgress(
            songKey = song.stableKey(),
            songId = song.id,
            fileName = "song.mp3",
            bytesRead = bytesRead,
            totalBytes = 1_000L,
            speedBytesPerSec = 100L,
            stage = stage,
            attemptId = attemptId
        )
    }
}
