package moe.ouom.neriplayer.core.player.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownloadProgressPolicyTest {

    @Test
    fun `old operation cannot clear replacement progress`() {
        val replacement = progress(
            songKey = "song",
            attemptId = 22L,
            operationId = "replacement"
        )

        assertFalse(
            AudioDownloadProgressPolicy.shouldClearVisibleProgressForOwner(
                visible = replacement,
                songKey = "song",
                expectedAttemptId = 21L,
                expectedOperationId = "old"
            )
        )
        assertFalse(
            AudioDownloadProgressPolicy.shouldClearVisibleProgressForOwner(
                visible = replacement,
                songKey = "song",
                expectedOperationId = "old"
            )
        )
    }

    @Test
    fun `matching operation may clear its own progress`() {
        val current = progress(
            songKey = "song",
            attemptId = 22L,
            operationId = "current"
        )

        assertTrue(
            AudioDownloadProgressPolicy.shouldClearVisibleProgressForOwner(
                visible = current,
                songKey = "song",
                expectedAttemptId = 22L,
                expectedOperationId = "current"
            )
        )
    }

    @Test
    fun `unscoped user cancellation still clears the song progress`() {
        assertTrue(
            AudioDownloadProgressPolicy.shouldClearVisibleProgressForOwner(
                visible = progress("song", 1L, "operation"),
                songKey = "song"
            )
        )
        assertFalse(
            AudioDownloadProgressPolicy.shouldClearVisibleProgressForOwner(
                visible = progress("other", 1L, "operation"),
                songKey = "song"
            )
        )
    }

    private fun progress(
        songKey: String,
        attemptId: Long,
        operationId: String
    ): AudioDownloadManager.DownloadProgress {
        return AudioDownloadManager.DownloadProgress(
            songKey = songKey,
            songId = 1L,
            fileName = "$songKey.mp3",
            bytesRead = 1L,
            totalBytes = 2L,
            speedBytesPerSec = 1L,
            attemptId = attemptId,
            operationId = operationId
        )
    }
}
