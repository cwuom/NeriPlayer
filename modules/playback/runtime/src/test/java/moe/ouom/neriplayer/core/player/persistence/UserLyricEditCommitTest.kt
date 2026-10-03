package moe.ouom.neriplayer.core.player.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UserLyricEditCommitTest {
    private val edit = SongItem(1L, "song", "artist", "netease", 0L, 1L, null,
        matchedLyric = "edit", lyricSyncEdited = true, lyricSyncRevision = 20L)

    @Test
    fun `failed durable edit returns failure before queue publication or playback persistence`() = runTest {
        val calls = mutableListOf<String>()
        val committed = recordUserLyricEditThenPublish({ edit }, {
            calls += "record"
            throw IOException("storage unavailable")
        }) {
            calls += "queue"
            calls += "persist"
        }
        assertFalse(committed)
        assertEquals(listOf("record"), calls)
    }

    @Test
    fun `success publishes only after the exact edit is durably recorded`() = runTest {
        val calls = mutableListOf<String>()
        assertTrue(recordUserLyricEditThenPublish({ edit }, {
            assertEquals(edit, it)
            calls += "record"
        }) {
            calls += "queue"
            calls += "persist"
        })
        assertEquals(listOf("record", "queue", "persist"), calls)
    }

    @Test
    fun `cancellation is propagated without publishing an uncommitted edit`() = runTest {
        val cancelled = CancellationException("cancelled")
        var published = false
        val failure = runCatching {
            recordUserLyricEditThenPublish({ edit }, { throw cancelled }) { published = true }
        }.exceptionOrNull()
        assertEquals(cancelled, failure)
        assertFalse(published)
    }

    @Test
    fun `failed revision lookup returns failure before durable write or publication`() = runTest {
        val calls = mutableListOf<String>()
        val committed = recordUserLyricEditThenPublish({ throw IOException("registry unavailable") }, {
            calls += "record"
        }) { calls += "publish" }
        assertFalse(committed)
        assertTrue(calls.isEmpty())
    }
}
