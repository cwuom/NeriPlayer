package moe.ouom.neriplayer.platform.netease.playlist

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteasePlaylistBatchAddTest {
    private val noPause: (Long) -> Unit = { error("unexpected backoff") }
    private val badSong = NeteasePlaylistAddOutcome.Rejected(500, "bad song")

    @Test
    fun `batch add splits failed chunks and keeps syncing remaining songs`() = runTest {
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
    fun `batch add filters invalid and duplicate song ids before submitting`() = runTest {
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
    fun `empty and invalid batch inputs never submit a request`() = runTest {
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
                runTest {
                    addNeteasePlaylistSongIdsInBatches(listOf(1L), size, noPause) { error("invalid batch size must not submit") }
                }
            }
        }
    }

    @Test
    fun `request exceptions back off and retry the same batch without splitting`() = runTest {
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
    fun `request cancellation is propagated without retrying or submitting later batches`() {
        val calls = mutableListOf<List<Long>>()
        val cancellation = CancellationException("sync cancelled")

        val error = assertThrows(CancellationException::class.java) {
            runTest {
                addNeteasePlaylistSongIdsInBatches(listOf(1L, 2L), 1, noPause) { ids ->
                    calls += ids.toList()
                    throw cancellation
                }
            }
        }

        assertEquals(cancellation, error)
        assertEquals(listOf(listOf(1L)), calls)
    }

    @Test
    fun `cancellation after a rejected request stops splitting and later batch requests`() = runTest {
        val calls = mutableListOf<List<Long>>()
        val pauses = mutableListOf<Long>()
        val cancellation = CancellationException("sync cancelled")
        val sync = async {
            addNeteasePlaylistSongIdsInBatches(listOf(1L, 2L, 3L), 2, pauses::add) { ids ->
                calls += ids.toList()
                if (calls.size == 1) currentCoroutineContext().cancel(cancellation)
                badSong
            }
        }

        val error = runCatching { sync.await() }.exceptionOrNull()

        assertTrue(error is CancellationException)
        assertEquals(cancellation.message, error?.message)
        assertEquals(listOf(listOf(1L, 2L)), calls)
        assertEquals(emptyList<Long>(), pauses)
    }

    @Test
    fun `whole batch rejection codes reject every song without splitting`() = runTest {
        for (code in listOf(301, 401)) {
            val calls = mutableListOf<List<Long>>()
            val rejected = NeteasePlaylistAddOutcome.Rejected(code, "login required")
            val result = addNeteasePlaylistSongIdsInBatches(listOf(1L, 2L, 3L, 4L), 50, noPause) { ids ->
                calls += ids.toList()
                rejected
            }

            assertEquals(listOf(listOf(1L, 2L, 3L, 4L)), calls)
            assertEquals(emptySet<Long>(), result.addedIds)
            assertEquals(setOf(1L, 2L, 3L, 4L), result.failedIds)
            assertEquals((1L..4L).associateWith { rejected }, result.rejections)
        }
    }

    @Test
    fun `524 isolates rejected songs without blocking the rest of a mixed batch`() = runTest {
        val calls = mutableListOf<List<Long>>()
        val rejected = NeteasePlaylistAddOutcome.Rejected(524, "当前环境异常，已取消喜欢")

        val result = addNeteasePlaylistSongIdsInBatches((1L..14L).toList(), 50, noPause) { ids ->
            calls += ids.toList()
            if (4L in ids || 9L in ids) rejected else NeteasePlaylistAddOutcome.Ok
        }

        assertEquals((1L..14L).toSet() - setOf(4L, 9L), result.addedIds)
        assertEquals(setOf(4L, 9L), result.failedIds)
        assertEquals(mapOf(4L to rejected, 9L to rejected), result.rejections)
        assertTrue(listOf(4L) in calls)
        assertTrue(listOf(9L) in calls)
    }

    @Test
    fun `four rejected songs across 88 tracks do not prevent healthy songs from being submitted`() = runTest {
        val calls = mutableListOf<List<Long>>()
        val songIds = (1L..88L).toList()
        val rejectedIds = setOf(1L, 26L, 51L, 76L)
        val rejected = NeteasePlaylistAddOutcome.Rejected(524, "当前环境异常，已取消喜欢")

        val result = addNeteasePlaylistSongIdsInBatches(songIds, 50, noPause) { ids ->
            calls += ids.toList()
            if (ids.any { it in rejectedIds }) rejected else NeteasePlaylistAddOutcome.Ok
        }

        assertEquals(songIds.toSet() - rejectedIds, result.addedIds)
        assertEquals(rejectedIds, result.failedIds)
        assertEquals(rejectedIds.associateWith { rejected }, result.rejections)
        assertEquals(rejectedIds, calls.filter { it.size == 1 && it.single() in rejectedIds }.flatten().toSet())
        assertTrue(calls.size <= songIds.size * 2 - 2)
    }

    @Test
    fun `rate limited batches back off exponentially and stop the remaining batches`() = runTest {
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
    fun `splitting rejected batches attempts every song within a linear request bound`() = runTest {
        val calls = mutableListOf<List<Long>>()
        val songIds = (1L..200L).toList()

        val result = addNeteasePlaylistSongIdsInBatches(songIds, 50, noPause) { ids ->
            calls += ids.toList()
            badSong
        }

        assertTrue(result.addedIds.isEmpty())
        assertEquals(songIds.toSet(), result.failedIds)
        assertEquals(songIds.associateWith { badSong }, result.rejections)
        assertEquals(songIds, calls.filter { it.size == 1 }.flatten())
        assertTrue(calls.size <= songIds.size * 2 - 4)
    }

    @Test
    fun `persistent 524 attempts every song before classifying rejections within a linear bound`() = runTest {
        val calls = mutableListOf<List<Long>>()
        val songIds = (1L..200L).toList()
        val rejected = NeteasePlaylistAddOutcome.Rejected(524, "当前环境异常，已取消喜欢")

        val result = addNeteasePlaylistSongIdsInBatches(songIds, 50, noPause) { ids ->
            calls += ids.toList()
            rejected
        }

        assertTrue(result.addedIds.isEmpty())
        assertEquals(songIds.toSet(), result.failedIds)
        assertEquals(songIds.associateWith { rejected }, result.rejections)
        assertEquals(songIds, calls.filter { it.size == 1 }.flatten())
        assertTrue(calls.size <= songIds.size * 2 - 4)
    }

    @Test
    fun `a fully rejected batch preserves the next healthy batch submission`() = runTest {
        val calls = mutableListOf<List<Long>>()
        val rejected = NeteasePlaylistAddOutcome.Rejected(524, "当前环境异常，已取消喜欢")
        val rejectedIds = (1L..50L).toList()
        val healthyIds = (51L..100L).toList()

        val result = addNeteasePlaylistSongIdsInBatches((1L..100L).toList(), 50, noPause) { ids ->
            calls += ids.toList()
            if (ids.any { it <= 50L }) rejected else NeteasePlaylistAddOutcome.Ok
        }

        assertEquals(healthyIds.toSet(), result.addedIds)
        assertEquals(rejectedIds.toSet(), result.failedIds)
        assertEquals(rejectedIds.associateWith { rejected }, result.rejections)
        assertEquals(healthyIds, calls.last())
        assertEquals(rejectedIds, calls.filter { it.size == 1 }.flatten())
        assertTrue(calls.size <= rejectedIds.size * 2)
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
