package moe.ouom.neriplayer.core.download.generation

import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun `batch cancellation snapshots and invalidates only distinct valid keys`() {
        val first = song(1L)
        val second = song(2L)
        val untouched = song(3L)
        val tracker = DownloadRequestGenerationTracker()
        val generation = tracker.begin(listOf(first, second, untouched)).generation

        val cancellation = tracker.snapshotAndInvalidate(
            listOf(
                first.stableKey(),
                "",
                second.stableKey(),
                "   ",
                first.stableKey()
            )
        )

        assertEquals(2, cancellation.invalidatedCount)
        assertEquals(
            mapOf(
                first.stableKey() to generation,
                second.stableKey() to generation
            ),
            cancellation.generationsBySongKey
        )
        assertNull(tracker.currentGeneration(first.stableKey()))
        assertNull(tracker.currentGeneration(second.stableKey()))
        assertEquals(generation, tracker.currentGeneration(untouched.stableKey()))
    }

    @Test
    fun `fresh generation takes ownership after a batch cancellation snapshot`() {
        val target = song(1L)
        val tracker = DownloadRequestGenerationTracker()
        val cancelledGeneration = tracker.begin(listOf(target)).generation
        val cancellation = tracker.snapshotAndInvalidate(listOf(target.stableKey()))

        assertTrue(
            tracker.shouldKeepCancellationCleanup(
                songKey = target.stableKey(),
                cancellationGeneration = cancellation.generationsBySongKey[target.stableKey()],
                cancelled = true
            )
        )

        val replacementGeneration = tracker.begin(listOf(target)).generation

        assertTrue(replacementGeneration > cancelledGeneration)
        assertFalse(
            tracker.shouldKeepCancellationCleanup(
                songKey = target.stableKey(),
                cancellationGeneration = cancellation.generationsBySongKey[target.stableKey()],
                cancelled = true
            )
        )
    }

    @Test
    fun `taskless cancellation stops when a fresh start clears its marker`() {
        val target = song(1L)
        val tracker = DownloadRequestGenerationTracker()
        val cancellation = tracker.snapshotAndInvalidate(listOf(target.stableKey()))

        assertTrue(
            tracker.shouldKeepCancellationCleanup(
                songKey = target.stableKey(),
                cancellationGeneration = cancellation.generationsBySongKey[target.stableKey()],
                cancelled = true
            )
        )
        assertFalse(
            tracker.shouldKeepCancellationCleanup(
                songKey = target.stableKey(),
                cancellationGeneration = cancellation.generationsBySongKey[target.stableKey()],
                cancelled = false
            )
        )
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
