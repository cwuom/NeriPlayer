package moe.ouom.neriplayer.core.player.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadProgressEventStreamTest {

    @Test
    fun `concurrent song progress remains ordered without state flow conflation`() = runTest {
        val stream = DownloadProgressEventStream<AudioDownloadManager.DownloadProgress>(
            bufferCapacity = 4
        )
        val releaseCollector = CompletableDeferred<Unit>()
        val received = mutableListOf<AudioDownloadManager.DownloadProgress>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.take(3).collect { progress ->
                received += progress
                if (received.size == 1) {
                    releaseCollector.await()
                }
            }
        }
        val first = progress(songKey = "song-a", attemptId = 101L, bytesRead = 10L)
        val second = progress(songKey = "song-b", attemptId = 202L, bytesRead = 20L)
        val third = progress(songKey = "song-a", attemptId = 101L, bytesRead = 30L)

        stream.publish(first)
        stream.publish(second)
        stream.publish(third)

        releaseCollector.complete(Unit)
        collector.join()

        assertEquals(listOf(first, second, third), received)
        assertEquals(listOf("song-a", "song-b", "song-a"), received.map { it.songKey })
        assertEquals(listOf(101L, 202L, 101L), received.map { it.attemptId })
    }

    @Test
    fun `slow consumer keeps publishing nonblocking and receives the newest progress`() = runTest {
        val stream = DownloadProgressEventStream<AudioDownloadManager.DownloadProgress>(
            bufferCapacity = 1
        )
        val releaseCollector = CompletableDeferred<Unit>()
        val received = mutableListOf<AudioDownloadManager.DownloadProgress>()
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            stream.events.take(2).collect { progress ->
                received += progress
                if (received.size == 1) {
                    releaseCollector.await()
                }
            }
        }
        val first = progress(songKey = "song-a", attemptId = 101L, bytesRead = 10L)
        val dropped = progress(songKey = "song-a", attemptId = 101L, bytesRead = 20L)
        val latest = progress(songKey = "song-a", attemptId = 101L, bytesRead = 30L)

        stream.publish(first)
        stream.publish(dropped)
        stream.publish(latest)
        releaseCollector.complete(Unit)
        collector.join()

        assertEquals(listOf(first, latest), received)
    }

    @Test
    fun `event buffer capacity must be positive`() {
        val error = runCatching {
            DownloadProgressEventStream<AudioDownloadManager.DownloadProgress>(bufferCapacity = 0)
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
    }

    private fun progress(
        songKey: String,
        attemptId: Long,
        bytesRead: Long
    ): AudioDownloadManager.DownloadProgress {
        return AudioDownloadManager.DownloadProgress(
            songKey = songKey,
            songId = songKey.hashCode().toLong(),
            fileName = "$songKey.flac",
            bytesRead = bytesRead,
            totalBytes = 100L,
            speedBytesPerSec = 10L,
            attemptId = attemptId
        )
    }
}
