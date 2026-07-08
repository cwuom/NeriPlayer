package moe.ouom.neriplayer.core.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import moe.ouom.neriplayer.data.model.stableKey
import moe.ouom.neriplayer.ui.viewmodel.playlist.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTaskStoreTest {

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
}
