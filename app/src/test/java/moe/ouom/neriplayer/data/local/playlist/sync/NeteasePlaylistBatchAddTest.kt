package moe.ouom.neriplayer.data.local.playlist.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class NeteasePlaylistBatchAddTest {
    @Test
    fun `batch add splits failed chunks and keeps syncing remaining songs`() {
        val calls = mutableListOf<List<Long>>()

        val result = addNeteasePlaylistSongIdsInBatches(
            songIds = listOf(1L, 2L, 3L, 4L, 5L),
            batchSize = 4
        ) { ids ->
            calls += ids.toList()
            3L !in ids
        }

        assertEquals(setOf(1L, 2L, 4L, 5L), result.addedIds)
        assertEquals(setOf(3L), result.failedIds)
        assertEquals(
            listOf(
                listOf(1L, 2L, 3L, 4L),
                listOf(1L, 2L),
                listOf(3L, 4L),
                listOf(3L),
                listOf(4L),
                listOf(5L)
            ),
            calls
        )
    }

    @Test
    fun `batch add filters invalid and duplicate song ids before submitting`() {
        val calls = mutableListOf<List<Long>>()

        val result = addNeteasePlaylistSongIdsInBatches(
            songIds = listOf(0L, 6L, 6L, -1L, 7L),
            batchSize = 50
        ) { ids ->
            calls += ids.toList()
            true
        }

        assertEquals(setOf(6L, 7L), result.addedIds)
        assertEquals(emptySet<Long>(), result.failedIds)
        assertEquals(listOf(listOf(6L, 7L)), calls)
    }

    @Test
    fun `failed song resolution batches lookups and skips only confirmed unsupported ids`() {
        val calls = mutableListOf<List<Long>>()

        val result = classifyNeteasePlaylistAddFailures(
            failedIds = (1L..7L).toList(),
            batchSize = 3
        ) { ids ->
            calls += ids.toList()
            ids.filter { it % 2L == 1L }.toSet()
        }

        assertEquals(
            linkedSetOf(1L, 3L, 5L, 7L),
            result.unresolvedFailedIds
        )
        assertEquals(3, result.skippedUnsupported)
        assertEquals(
            listOf(
                listOf(1L, 2L, 3L),
                listOf(4L, 5L, 6L),
                listOf(7L)
            ),
            calls
        )
    }

    @Test
    fun `failed song resolution keeps unknown lookup results as failed`() {
        val calls = mutableListOf<List<Long>>()

        val result = classifyNeteasePlaylistAddFailures(
            failedIds = listOf(1L, 2L, 3L, 4L),
            batchSize = 3
        ) { ids ->
            calls += ids.toList()
            null
        }

        assertEquals(linkedSetOf(1L, 2L, 3L, 4L), result.unresolvedFailedIds)
        assertEquals(0, result.skippedUnsupported)
        assertEquals(
            listOf(listOf(1L, 2L, 3L), listOf(4L)),
            calls
        )
    }
}
