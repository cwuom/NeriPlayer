package moe.ouom.neriplayer.core.download.generation

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadRequestGenerationTrackerTest {
    @Test
    fun `active worker reuses its batch generation instead of replacing it`() {
        val first = song(1L)
        val second = song(2L)
        val tracker = DownloadRequestGenerationTracker()
        val batchGeneration = tracker.begin(listOf(first, second)).generation

        val reusedGeneration = tracker.reuseOrBegin(first, reuseCurrent = true)

        assertEquals(batchGeneration, reusedGeneration)
        assertEquals(batchGeneration, tracker.currentGeneration(first.stableKey()))
        assertEquals(batchGeneration, tracker.currentGeneration(second.stableKey()))

        val replacementGeneration = tracker.reuseOrBegin(first, reuseCurrent = false)

        assertTrue(replacementGeneration > batchGeneration)
        assertEquals(replacementGeneration, tracker.currentGeneration(first.stableKey()))
        assertEquals(batchGeneration, tracker.currentGeneration(second.stableKey()))
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
}
