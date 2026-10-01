package moe.ouom.neriplayer.data.platform.netease.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `empty and invalid batch inputs never submit a request`() {
        for (ids in listOf(emptyList(), listOf(0L, -1L))) {
            assertEquals(
                NeteasePlaylistBatchAddResult(emptySet(), emptySet()),
                addNeteasePlaylistSongIdsInBatches(ids, 50) { error("empty input must not submit") }
            )
        }
    }

    @Test
    fun `nonpositive batch sizes reject the request before submitting`() {
        for (size in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                addNeteasePlaylistSongIdsInBatches(listOf(1L), size) { error("invalid batch size must not submit") }
            }
        }
    }

    @Test
    fun `request exceptions retain failed singleton ids and continue other batches`() {
        val calls = mutableListOf<List<Long>>()
        val result = addNeteasePlaylistSongIdsInBatches(listOf(1L, 2L, 3L), 2) { ids ->
            calls += ids
            if (1L in ids) throw IllegalStateException("request failed")
            true
        }

        assertEquals(setOf(2L, 3L), result.addedIds)
        assertEquals(setOf(1L), result.failedIds)
        assertEquals(listOf(listOf(1L, 2L), listOf(1L), listOf(2L), listOf(3L)), calls)
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
