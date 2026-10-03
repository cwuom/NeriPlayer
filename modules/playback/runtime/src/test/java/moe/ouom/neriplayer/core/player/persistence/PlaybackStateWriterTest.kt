package moe.ouom.neriplayer.core.player.persistence

import com.google.gson.Gson
import java.io.File
import java.io.IOException
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.model.playback.PersistedPlaybackState
import moe.ouom.neriplayer.data.model.playback.PersistedState
import moe.ouom.neriplayer.data.model.playback.queue.PlayerQueueSnapshot
import moe.ouom.neriplayer.core.player.persistence.withPlaybackState
import moe.ouom.neriplayer.data.model.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PlaybackStateWriterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `fallback cannot confirm progress until the primary marker is durable`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val queue = queue()
        writer.write(snapshot(queue, 1_000), room, legacy)
        room.failUpdate = true
        room.failMarker = true
        val failure = runCatching { writer.write(snapshot(queue, 2_000), room, legacy) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(2_000L, legacy.read()?.positionMs)
        room.failUpdate = false
        room.failMarker = false
        writer.write(snapshot(queue, 3_000), room, legacy)
        assertEquals(listOf("replace", "update", "legacy", "replace"), room.operations)
        assertEquals(3_000L, room.state?.positionMs)
    }

    @Test
    fun `fallback cannot confirm an empty queue until the primary marker is durable`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        room.failClear = true
        room.failMarker = true
        val empty = snapshot(PlayerQueueSnapshot.EMPTY, 0)
        val failure = runCatching { writer.write(empty, room, legacy) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(emptyList<Any>(), legacy.read()?.playlist)
        room.failClear = false
        room.failMarker = false
        writer.write(empty, room, legacy)
        assertEquals(listOf("clear", "legacy", "clear"), room.operations)
        assertNull(legacy.read())
    }

    @Test
    fun `confirmed progress does not serialize the full queue and fallback still preserves it`() = runTest {
        var queueMaterializations = 0
        val writer = PlaybackStateWriter { value ->
            queueMaterializations++
            value.toPersistedState()
        }
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val queue = queue()
        writer.write(snapshot(queue, 1_000), room, legacy)
        assertEquals(1, queueMaterializations)
        writer.write(snapshot(queue, 2_000), room, legacy)
        assertEquals(1, queueMaterializations)
        assertEquals(2_000L, room.state?.positionMs)

        room.failUpdate = true
        assertEquals(PlaybackQueuePersistTarget.LEGACY_JSON, writer.write(snapshot(queue, 3_000), room, legacy))
        assertEquals(2, queueMaterializations)
        assertEquals(listOf("New queue"), legacy.read()?.playlist?.map { it.name })
        assertEquals(3_000L, legacy.read()?.positionMs)
        room.failUpdate = false
        writer.write(snapshot(queue, 4_000), room, legacy)
        assertEquals(3, queueMaterializations)
        assertEquals(listOf("replace", "update", "update", "legacy", "replace"), room.operations)
    }

    @Test
    fun `confirmed queue needs only progress updates and identical saves are skipped`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val queue = queue()

        assertEquals(PlaybackQueuePersistTarget.ROOM, writer.write(snapshot(queue, 1_000L), room, legacy))
        assertEquals(PlaybackQueuePersistTarget.ROOM, writer.write(snapshot(queue, 2_000L), room, legacy))
        assertEquals(PlaybackQueuePersistTarget.NONE, writer.write(snapshot(queue, 2_000L), room, legacy))
        assertEquals(listOf("replace", "update"), room.operations)
        assertEquals(2_000L, room.state?.positionMs)
    }

    @Test
    fun `changing mode memory updates the stored restore queue even when live queues are unchanged`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val queue = queue()
        val withoutModeMemory = PlaybackStatePersistenceSnapshot(
            queue, PersistedPlaybackState(0, shuffleEnabled = false), queue.playlist, 0
        )
        val withModeMemory = PlaybackStatePersistenceSnapshot(
            queue, PersistedPlaybackState(0, shuffleEnabled = true), queue.playlist, 0
        )

        writer.write(withoutModeMemory, room, legacy)
        assertNull(room.state?.shuffleRestorePlaylist)
        writer.write(withModeMemory, room, legacy)
        assertEquals(listOf(1L), room.state?.shuffleRestorePlaylist?.map { it.id })
        assertEquals(0, room.state?.shuffleRestoreIndex)
        writer.write(withoutModeMemory, room, legacy)
        assertNull(room.state?.shuffleRestorePlaylist)
        assertNull(room.state?.shuffleRestoreIndex)
    }

    @Test
    fun `JSON fallback must replace the entire Room queue before incremental updates resume`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val queue = queue()
        room.failReplacement = true

        assertEquals(PlaybackQueuePersistTarget.LEGACY_JSON, writer.write(snapshot(queue, 1_000L), room, legacy))
        assertEquals(listOf("New queue"), legacy.read()?.playlist?.map { it.name })
        room.failReplacement = false
        assertEquals(PlaybackQueuePersistTarget.ROOM, writer.write(snapshot(queue, 2_000L), room, legacy))
        writer.write(snapshot(queue, 3_000L), room, legacy)

        assertEquals(listOf("replace", "legacy", "replace", "update"), room.operations)
        assertEquals(listOf("New queue"), room.state?.playlist?.map { it.name })
        assertEquals(3_000L, room.state?.positionMs)
        assertNull(legacy.read())
    }

    @Test
    fun `a successful Room save removes legacy snapshots even when their timestamp is in the future`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        legacy.write(PersistedState(emptyList(), -1), PersistedPlaybackState(-1))
        assertTrue(File(temporaryFolder.root, "queue.json").setLastModified(System.currentTimeMillis() + 86_400_000L))

        writer.write(snapshot(queue(), 2_000L), room, legacy)

        assertNull(legacy.read())
        assertEquals(listOf("New queue"), room.state?.playlist?.map { it.name })
    }

    @Test
    fun `legacy cleanup failure is reported instead of confirming stale files as removed`() {
        val queueDirectory = temporaryFolder.newFolder("queue.json")
        File(queueDirectory, "child").writeText("keeps the directory nonempty")
        val failure = try {
            legacyStore().clear()
            null
        } catch (error: IOException) {
            error
        }

        assertNotNull(failure)
    }

    @Test
    fun `failed clear retries the Room clear after falling back to an empty JSON queue`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val empty = snapshot(PlayerQueueSnapshot.EMPTY, 0L)
        room.failClear = true

        assertEquals(PlaybackQueuePersistTarget.LEGACY_JSON, writer.write(empty, room, legacy))
        assertEquals(emptyList<Any>(), legacy.read()?.playlist)
        room.failClear = false
        assertEquals(PlaybackQueuePersistTarget.ROOM, writer.write(empty, room, legacy))
        assertEquals(listOf("clear", "legacy", "clear"), room.operations)
        assertNull(legacy.read())
    }

    @Test
    fun `an invalidation during storage IO cannot be undone by the old completion`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val legacy = legacyStore()
        val snapshot = snapshot(queue(), 1_000L)
        room.beforeReplace = { writer.invalidate() }
        writer.write(snapshot, room, legacy)
        room.beforeReplace = {}
        writer.write(snapshot, room, legacy)

        assertEquals(listOf("replace", "replace"), room.operations)
    }

    @Test
    fun `failure of both stores does not confirm a queue that was never saved`() = runTest {
        val writer = PlaybackStateWriter()
        val room = MemoryRoomStore()
        val blocker = temporaryFolder.newFile("blocked")
        val legacy = PlaybackQueueLegacyStore(File(blocker, "queue"), File(blocker, "playback"), Gson())
        val snapshot = snapshot(queue(), 1_000L)
        room.failReplacement = true
        val failure = try {
            writer.write(snapshot, room, legacy)
            null
        } catch (error: IOException) {
            error
        }
        assertNotNull(failure)
        room.failReplacement = false
        writer.write(snapshot, room, legacyStore())

        assertEquals(listOf("replace", "replace"), room.operations)
        assertEquals(1_000L, room.state?.positionMs)
    }

    private fun legacyStore() = PlaybackQueueLegacyStore(
        File(temporaryFolder.root, "queue.json"),
        File(temporaryFolder.root, "playback.json"),
        Gson()
    )

    private fun queue() = PlayerQueueSnapshot.from(
        listOf(SongItem(1L, "New queue", "Artist", "Album", 1L, 180_000L, null)),
        currentIndex = 0
    )

    private fun snapshot(queue: PlayerQueueSnapshot, positionMs: Long) = PlaybackStatePersistenceSnapshot(
        queue, PersistedPlaybackState(queue.currentIndex, positionMs = positionMs), null, -1
    )

    private class MemoryRoomStore : PlaybackQueueStateStore {
        val operations = mutableListOf<String>()
        var state: PersistedState? = null
        var failReplacement = false
        var failUpdate = false
        var failClear = false
        var failMarker = false
        var beforeReplace: () -> Unit = {}

        override suspend fun replaceSnapshot(state: PersistedState, now: Long) {
            operations.add("replace")
            beforeReplace()
            if (failReplacement) throw IOException("Room replacement unavailable")
            this.state = state
        }

        override suspend fun updatePlaybackState(state: PersistedPlaybackState, now: Long) {
            operations.add("update")
            if (failUpdate) throw IOException("Room progress update unavailable")
            this.state = checkNotNull(this.state).withPlaybackState(state)
        }

        override suspend fun clear(now: Long) {
            operations.add("clear")
            if (failClear) throw IOException("Room clear unavailable")
            state = null
        }

        override suspend fun markLegacyJsonPrimary(now: Long) {
            operations.add("legacy")
            if (failMarker) throw IOException("Room primary marker unavailable")
        }
    }
}
