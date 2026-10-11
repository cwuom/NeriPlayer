package moe.ouom.neriplayer.platform.netease.playlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaylistBatchAddTest {
    private val noPause: (Long) -> Unit = { error("unexpected backoff") }
    private val badSong = NeteasePlaylistAddOutcome.Rejected(500, "bad song")

    @Test
    fun `batch add splits failed chunks and keeps syncing remaining songs`() {
        val calls = mutableListOf<List<Long>>()

        val result = addNeteasePlaylistSongIdsInBatches(
            songIds = listOf(1L, 2L, 3L, 4L, 5L),
            batchSize = 4,
            pause = noPause
        ) { ids ->
            calls += ids.toList()
            if (3L in ids) badSong else NeteasePlaylistAddOutcome.Ok
        }

        assertEquals(setOf(1L, 2L, 4L, 5L), result.addedIds)
        assertEquals(setOf(3L), result.failedIds)
        assertEquals(mapOf(3L to badSong), result.rejections)
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
            batchSize = 50,
            pause = noPause
        ) { ids ->
            calls += ids.toList()
            NeteasePlaylistAddOutcome.Ok
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
                addNeteasePlaylistSongIdsInBatches(ids, 50, noPause) { error("empty input must not submit") }
            )
        }
    }

    @Test
    fun `nonpositive batch sizes reject the request before submitting`() {
        for (size in listOf(0, -1)) {
            assertThrows(IllegalArgumentException::class.java) {
                addNeteasePlaylistSongIdsInBatches(listOf(1L), size, noPause) { error("invalid batch size must not submit") }
            }
        }
    }

    @Test
    fun `request exceptions back off and retry the same batch without splitting`() {
        val calls = mutableListOf<List<Long>>()
        val pauses = mutableListOf<Long>()
        val result = addNeteasePlaylistSongIdsInBatches(listOf(1L, 2L, 3L), 2, pauses::add) { ids ->
            calls += ids.toList()
            if (calls.size == 1) throw IllegalStateException("request failed")
            NeteasePlaylistAddOutcome.Ok
        }

        assertEquals(setOf(1L, 2L, 3L), result.addedIds)
        assertEquals(emptySet<Long>(), result.failedIds)
        assertEquals(listOf(listOf(1L, 2L), listOf(1L, 2L), listOf(3L)), calls)
        assertEquals(listOf(1_000L), pauses)
    }

    @Test
    fun `whole batch rejection codes reject every song without splitting`() {
        val calls = mutableListOf<List<Long>>()
        val noCopyright = NeteasePlaylistAddOutcome.Rejected(524, "no copyright")

        val result = addNeteasePlaylistSongIdsInBatches(listOf(1L, 2L, 3L, 4L), 50, noPause) { ids ->
            calls += ids.toList()
            noCopyright
        }

        assertEquals(listOf(listOf(1L, 2L, 3L, 4L)), calls)
        assertEquals(emptySet<Long>(), result.addedIds)
        assertEquals(setOf(1L, 2L, 3L, 4L), result.failedIds)
        assertEquals((1L..4L).associateWith { noCopyright }, result.rejections)
    }

    @Test
    fun `rate limited batches back off exponentially and stop the remaining batches`() {
        val calls = mutableListOf<List<Long>>()
        val pauses = mutableListOf<Long>()
        val rateLimited = NeteasePlaylistAddOutcome.Transient(405, "too fast")

        val result = addNeteasePlaylistSongIdsInBatches((1L..6L).toList(), 2, pauses::add) { ids ->
            calls += ids.toList()
            rateLimited
        }

        assertEquals(List(4) { listOf(1L, 2L) }, calls)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), pauses)
        assertEquals(emptySet<Long>(), result.addedIds)
        assertEquals((1L..6L).toSet(), result.failedIds)
        assertEquals(emptyMap<Long, NeteasePlaylistAddOutcome.Rejected>(), result.rejections)
    }

    @Test
    fun `splitting rejected batches stops at the total request budget`() {
        val calls = mutableListOf<List<Long>>()

        val result = addNeteasePlaylistSongIdsInBatches((1L..200L).toList(), 50, noPause) { ids ->
            calls += ids.toList()
            badSong
        }

        assertEquals(4 + 30, calls.size)
        assertEquals((1L..200L).toSet(), result.failedIds)
        assertTrue(result.rejections.keys.containsAll(calls.filter { it.size == 1 }.flatten()))
        assertTrue(result.rejections.size < 200)
    }

    @Test
    fun `a rejection without a server message still explains its code`() {
        assertEquals("code 524", NeteasePlaylistAddOutcome.Rejected(524, null).reason)
        assertEquals("no copyright", NeteasePlaylistAddOutcome.Rejected(524, "no copyright").reason)
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
