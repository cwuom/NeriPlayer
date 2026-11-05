@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.ouom.neriplayer.data.history

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.PlayHistoryRoomStore
import moe.ouom.neriplayer.data.local.database.store.mockPlayHistoryRoomStore
import moe.ouom.neriplayer.data.model.history.PlayedEntry
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.model.sync.SyncSong
import moe.ouom.neriplayer.data.sync.CoverUrlMapper
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.sync.store.secure.SyncStorageUnavailableException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlayHistoryRepositoryPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `record keeps saved history and old deletion when secure cleanup is unavailable and retries after recovery`() = runTest {
        assertSavedHistoryOnCleanupFailure { repository, song, now -> repository.record(song, now) }
    }

    @Test
    fun `remembered position keeps saved history and old deletion when secure cleanup is unavailable and retries after recovery`() = runTest {
        assertSavedHistoryOnCleanupFailure(expectedPosition = 50L) { repository, song, now ->
            repository.updateRememberedPlaybackPosition(song, now / 2L, now)
        }
    }

    @Test
    fun `unrelated deletion cleanup errors remain visible instead of being treated as a keystore outage`() = runTest {
        assertSavedHistoryOnCleanupFailure(failure = IllegalStateException("unexpected cleanup failure")) { repository, song, now ->
            repository.record(song, now)
        }
    }

    @Test
    fun `history clear preserves local rows and deletion records when secure device identity cannot be read`() = runTest {
        assertRejectedSecureHistoryChange(
            fail = { storage -> doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).getOrCreateDeviceId() },
            change = PlayHistoryRepository::clear
        )
    }

    @Test
    fun `history removal preserves local rows and prior deletion records when a new deletion cannot be stored`() = runTest {
        assertRejectedSecureHistoryChange(
            fail = { storage -> doAnswer { throw SyncStorageUnavailableException() }.`when`(storage).addRecentPlayDeletions(anyList()) },
            change = { repository -> repository.removeSongs(listOf(entry(1).toSongItem())) }
        )
    }

    @Test
    fun `history metadata remains unchanged when its durable lyric override cannot be recorded`() = runTest {
        CoverUrlMapper.installForTest(CoverUrlMapper.createForTest())
        try {
            val storage = assertRejectedSecureHistoryChange(
                fail = { storage ->
                    doAnswer { throw SyncStorageUnavailableException() }.`when`(storage)
                        .recordLyricOverride(any(SyncSong::class.java) ?: SyncSong())
                },
                change = { repository ->
                    val song = entry(1).toSongItem()
                    repository.updateSongMetadata(song, song.copy(name = "edited", lyricSyncEdited = true,
                        lyricSyncRevision = 10L, matchedLyric = "edited lyric"), triggerSync = false)
                }
            )
            verify(storage).recordLyricOverride(any(SyncSong::class.java) ?: SyncSong())
        } finally {
            CoverUrlMapper.installForTest(null)
        }
    }

    private suspend fun TestScope.assertSavedHistoryOnCleanupFailure(
        expectedPosition: Long = 0L,
        failure: Exception = SyncStorageUnavailableException(),
        change: (PlayHistoryRepository, SongItem, Long) -> Unit
    ) {
        val room = mockPlayHistoryRoomStore()
        var primary = emptyList<PlayedEntry>()
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer { primary = it.getArgument(1); Unit }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        var unavailable = true
        val storage = historyStorageWithDurableDeletions { failure.takeIf { unavailable } }
        val deletion = SyncRecentPlayDeletion(3L, "netease", deletedAt = 2L, deviceId = "other-device")
        storage.addRecentPlayDeletions(listOf(deletion))
        val failures = mutableListOf<Throwable>()
        Fixture(room, this, storage, uncaughtFailures = failures).use { fixture ->
            val song = entry(3).toSongItem()
            change(fixture.repository, song, 100L)
            runCurrent()

            assertEquals(expectedPosition, primary.single().resumePositionMs)
            assertEquals(100L, primary.single().playedAt)
            assertEquals(primary, fixture.repository.syncSnapshot())
            assertEquals(listOf(deletion), storage.getRecentPlayDeletions())
            assertEquals(if (failure is SyncStorageUnavailableException) emptyList() else listOf(failure), failures)
            Fixture(room, storageOverride = storage).use { restarted ->
                assertEquals(primary, restarted.repository.syncSnapshot())
                assertEquals(listOf(deletion), restarted.storage.getRecentPlayDeletions())
            }

            unavailable = false
            change(fixture.repository, song, 200L)
            runCurrent()
            assertTrue(storage.getRecentPlayDeletions().isEmpty())
            assertEquals(200L, primary.single().playedAt)
            assertEquals(primary, fixture.repository.syncSnapshot())
        }
    }

    private suspend fun TestScope.assertRejectedSecureHistoryChange(
        fail: (SecureTokenStorage) -> Unit,
        change: (PlayHistoryRepository) -> Unit
    ): SecureTokenStorage {
        val original = listOf(entry(2), entry(1))
        val room = mockPlayHistoryRoomStore()
        var primary = original
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer { primary = it.getArgument(1); Unit }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        val storage = historyStorageWithDurableDeletions()
        val priorDeletion = SyncRecentPlayDeletion(9L, "netease", deletedAt = 2L, deviceId = "other-device")
        storage.addRecentPlayDeletions(listOf(priorDeletion))
        val failures = mutableListOf<Throwable>()
        Fixture(room, this, storage, uncaughtFailures = failures).use { fixture ->
            fail(storage)
            change(fixture.repository)
            runCurrent()

            assertEquals(original, primary)
            assertEquals(original, fixture.repository.syncSnapshot())
            assertEquals(listOf(priorDeletion), storage.getRecentPlayDeletions())
            assertEquals(emptyList<Throwable>(), failures)
        }
        return storage
    }

    @Test
    fun `recording and updating scanned history keeps its selected source after Room reload`() = runTest {
        val reference = "content://media/external/audio/media/3"
        val source = entry(3).toSongItem().copy(
            mediaUri = reference, channelId = "netease", audioId = "3",
            explicitLocalSourceReference = reference
        )
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(source.copy(explicitLocalSourceReference = null).toPlayedEntry(1L))
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer { primary = it.getArgument(1); Unit }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())

        Fixture(room, testScope = this).use { fixture ->
            fixture.repository.record(source, now = 2L)
            runCurrent()
            assertEquals(reference, fixture.repository.historyFlow.value.single().toSongItem().explicitLocalSourceReference)

            fixture.repository.record(source.copy(name = "Updated title", explicitLocalSourceReference = null), now = 3L)
            runCurrent()
            assertEquals(reference, primary.single().toSongItem().explicitLocalSourceReference)
            Fixture(room).use { restarted ->
                assertEquals(reference, restarted.repository.historyFlow.value.single().toSongItem().explicitLocalSourceReference)
                assertEquals("Updated title", restarted.repository.historyFlow.value.single().name)
            }
        }
    }

    @Test
    fun `changing a history source clears both valid old and stale unrelated provenance`() = runTest {
        val originalReference = "content://media/external/audio/media/3"
        val replacementReference = "content://media/external/audio/media/4"
        val source = entry(3).toSongItem().copy(mediaUri = originalReference,
            explicitLocalSourceReference = originalReference)
        listOf(originalReference, replacementReference).forEach { oldMarker ->
            val room = mockPlayHistoryRoomStore()
            var primary = listOf(source.toPlayedEntry(1L).copy(explicitLocalSourceReference = oldMarker))
            `when`(room.readIfRoomPrimary()).thenAnswer { primary }
            doAnswer { primary = it.getArgument(1); Unit }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())

            Fixture(room, testScope = this).use { fixture ->
                val replacement = source.copy(mediaUri = replacementReference, explicitLocalSourceReference = null)
                fixture.repository.updateSongMetadata(source, replacement, triggerSync = false)
                runCurrent()

                assertEquals(replacementReference, primary.single().mediaUri)
                assertNull(primary.single().explicitLocalSourceReference)
                assertNull(primary.single().toSongItem().explicitLocalSourceReference)
            }
        }
    }

    @Test
    fun `unreadable Room primary cannot replace history from a stale JSON snapshot`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val stale = File(temporary.root, "play_history.json").also { it.writeText(com.google.gson.Gson().toJson(listOf(entry(1)))) }
        val originalBytes = stale.readBytes()
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary temporarily unavailable") }

        Fixture(room).use { fixture ->
            assertTrue(fixture.repository.historyFlow.value.isEmpty())
            assertFalse(fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7))
            assertTrue(originalBytes.contentEquals(stale.readBytes()))
            verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `history recovery retries the same primary and publishes every recovered identity`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var unavailable = true
        val complete = listOf(entry(2), entry(1))
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("primary unavailable") else complete
        }
        Fixture(room).use { fixture ->
            assertFalse(fixture.repository.awaitInitialized())
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            unavailable = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(complete, fixture.repository.syncSnapshot())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `unknown history refuses local replacement without changing primary or legacy bytes`() = runTest {
        val room = mockPlayHistoryRoomStore()
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary unavailable") }
        Fixture(room).use { fixture ->
            assertTrue(runCatching { fixture.repository.updateHistory(listOf(entry(2))) }.exceptionOrNull() is IOException)
            assertTrue(fixture.repository.historyFlow.value.isEmpty())
            assertFalse(File(temporary.root, "play_history.json").exists())
            verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            verify(fixture.storage, never()).markSyncMutation()
        }
    }

    @Test
    fun `history recovery cancellation propagates and cannot import legacy`() = runTest {
        val room = mockPlayHistoryRoomStore()
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary unavailable") }
        Fixture(room).use { fixture ->
            val cancelled = CancellationException("recovery cancelled")
            doAnswer { throw cancelled }.`when`(room).readIfRoomPrimary()
            assertCancellation(cancelled, runCatching { fixture.repository.awaitInitialized() }.exceptionOrNull())
            assertTrue(fixture.repository.historyFlow.value.isEmpty())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `invalid history JSON cannot promote an empty database and repaired JSON can recover`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val file = File(temporary.root, "play_history.json").also { it.writeText("null") }
        Fixture(room).use { fixture ->
            assertFalse(fixture.repository.awaitInitialized())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            assertEquals("null", file.readText())
            file.writeText(com.google.gson.Gson().toJson(listOf(entry(1))))
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(listOf(entry(1)), fixture.repository.syncSnapshot())
            verify(room).importLegacyAndPromote(org.mockito.ArgumentMatchers.eq(listOf(entry(1))) ?: emptyList(), anyLong())
        }
    }

    @Test
    fun `ordinary failed history save stays pending until the complete current snapshot is durable`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(1))
        var roomPrimary = true
        var markerFails = true
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) primary else null }
        doAnswer { throw IOException("Room write unavailable") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw IOException("import unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            if (markerFails) throw IOException("primary marker unavailable")
            roomPrimary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(room).use { fixture ->
            val current = listOf(entry(2), entry(1))
            fixture.repository.updateHistory(current)
            assertEquals(current, fixture.repository.historyFlow.value)
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            assertFalse(fixture.repository.awaitInitialized())
            assertEquals(listOf(entry(1)), primary)
            markerFails = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(current, fixture.repository.syncSnapshot())
            repeat(2) { assertTrue(fixture.repository.awaitInitialized()) }
            Fixture(room).use { restarted -> assertEquals(current, restarted.repository.syncSnapshot()) }
        }
    }

    @Test
    fun `failed history clear keeps permanent deletions but cannot acknowledge an uncommitted empty snapshot`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var roomPrimary = true
        var markerFails = true
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(entry(1)) else null }
        doAnswer { throw IOException("Room clear unavailable") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer {
            if (markerFails) throw IOException("marker unavailable")
            roomPrimary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(room, this).use { fixture ->
            fixture.repository.clear()
            runCurrent()
            assertTrue(fixture.repository.historyFlow.value.isEmpty())
            verify(fixture.storage).addRecentPlayDeletions(anyList())
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            assertFalse(fixture.repository.awaitInitialized())
            markerFails = false
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(fixture.repository.syncSnapshot().isEmpty())
            Fixture(room).use { restarted -> assertTrue(restarted.repository.historyFlow.value.isEmpty()) }
        }
    }

    @Test
    fun `committed then cancelled history sync recovers actual rows before later clear`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(1))
        var cancelAfterCommit = true
        val cancelled = CancellationException("committed before cancellation delivery")
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            val previous = it.getArgument<List<PlayedEntry>>(0).associateBy { row -> row.id }
            val next = it.getArgument<List<PlayedEntry>>(1).associateBy { row -> row.id }
            val rows = primary.associateBy { row -> row.id }.toMutableMap()
            (previous.keys - next.keys).forEach(rows::remove)
            next.forEach { (id, row) -> if (previous[id] != row) rows[id] = row }
            primary = rows.values.sortedByDescending { row -> row.playedAt }
            if (cancelAfterCommit) { cancelAfterCommit = false; throw cancelled }
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(room, this).use { fixture ->
            assertCancellation(cancelled, runCatching { fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7) }.exceptionOrNull())
            assertEquals(listOf(entry(2)), primary)
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(primary, fixture.repository.syncSnapshot())
            fixture.repository.clear()
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(primary.isEmpty())
            assertTrue(fixture.repository.syncSnapshot().isEmpty())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `committed then cancelled history fallback marker restores actual JSON authority`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var roomPrimary = true
        var cancelAfterMarker = true
        val cancelled = CancellationException("marker committed before cancellation delivery")
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(entry(1)) else null }
        doAnswer { throw IOException("Room write unavailable") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer {
            roomPrimary = false
            if (cancelAfterMarker) { cancelAfterMarker = false; throw cancelled }
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(room).use { fixture ->
            assertCancellation(cancelled, runCatching { fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7) }.exceptionOrNull())
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(listOf(entry(2)), fixture.repository.syncSnapshot())
            assertEquals(listOf(entry(2)), fixture.repository.historyFlow.value)
        }
    }

    @Test
    fun `ordinary committed history cancellation retains UI intent and recovers before the next delta`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(1))
        var unavailable = false
        val previousWrites = mutableListOf<List<PlayedEntry>>()
        val cancelled = CancellationException("ordinary save committed before cancellation")
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("authority temporarily unavailable")
            primary
        }
        doAnswer {
            val previous = it.getArgument<List<PlayedEntry>>(0)
            val next = it.getArgument<List<PlayedEntry>>(1)
            previousWrites += previous
            val rows = primary.associateBy { row -> row.id }.toMutableMap()
            (previous.map { row -> row.id }.toSet() - next.map { row -> row.id }.toSet()).forEach(rows::remove)
            next.forEach { row -> if (row !in previous) rows[row.id] = row }
            primary = rows.values.sortedByDescending { row -> row.playedAt }
            if (previousWrites.size == 1) throw cancelled
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(room).use { fixture ->
            assertCancellation(cancelled, runCatching { fixture.repository.updateHistory(listOf(entry(2))) }.exceptionOrNull())
            unavailable = true
            assertFalse(fixture.repository.awaitInitialized())
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            assertTrue(runCatching { fixture.repository.updateHistory(listOf(entry(3))) }.exceptionOrNull() is IOException)
            assertEquals(listOf(entry(2)), fixture.repository.historyFlow.value)
            unavailable = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(1, previousWrites.size)
            fixture.repository.updateHistory(listOf(entry(3)))
            assertEquals(listOf(entry(2)), previousWrites.last())
            assertEquals(listOf(entry(3)), primary)
            assertEquals(primary, fixture.repository.syncSnapshot())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `ordinary clear committed then cancelled keeps tombstones and recovers empty authority on reopen`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(2), entry(1))
        var writes = 0
        val storage = historyStorageWithDurableDeletions()
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            primary = applyHistoryDelta(primary, it.getArgument(0), it.getArgument(1))
            writes++
            if (writes == 1) throw CancellationException("clear committed before cancellation")
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(room, this, storage).use { fixture ->
            fixture.repository.clear()
            runCurrent()
            assertTrue(primary.isEmpty())
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            val deletions = storage.getRecentPlayDeletions()
            assertEquals(setOf(1L, 2L), deletions.map { it.songId }.toSet())
            assertTrue(deletions.all { it.deletedAt > 0 && it.deviceId == "device" })
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(fixture.repository.syncSnapshot().isEmpty())
            assertEquals(1, writes)
            Fixture(room, storageOverride = storage).use { reopened ->
                assertTrue(reopened.repository.syncSnapshot().isEmpty())
                assertEquals(deletions, reopened.storage.getRecentPlayDeletions())
                reopened.repository.updateHistory(listOf(entry(3)))
                assertEquals(listOf(entry(3)), primary)
            }
            assertEquals(deletions, storage.getRecentPlayDeletions())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `ordinary partial remove committed then cancelled retains surviving rows and deletion across reopen`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(2), entry(1))
        var writes = 0
        val storage = historyStorageWithDurableDeletions()
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            primary = applyHistoryDelta(primary, it.getArgument(0), it.getArgument(1))
            writes++
            if (writes == 1) throw CancellationException("partial removal committed before cancellation")
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(room, this, storage).use { fixture ->
            fixture.repository.removeSongs(listOf(entry(2).toSongItem()))
            runCurrent()
            assertEquals(listOf(entry(1)), primary)
            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            val deletions = storage.getRecentPlayDeletions()
            assertEquals(listOf(2L), deletions.map { it.songId })
            assertTrue(deletions.single().deletedAt > 0)
            assertEquals("device", deletions.single().deviceId)
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(primary, fixture.repository.syncSnapshot())
            assertEquals(1, writes)
            Fixture(room, storageOverride = storage).use { reopened ->
                assertEquals(listOf(entry(1)), reopened.repository.syncSnapshot())
                assertEquals(deletions, reopened.storage.getRecentPlayDeletions())
                reopened.repository.updateHistory(listOf(entry(3), entry(1)))
                assertEquals(listOf(entry(3), entry(1)), primary)
            }
            assertEquals(deletions, storage.getRecentPlayDeletions())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `failed fallback marker never acknowledges and same epoch retries before restart`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val original = listOf(entry(1))
        val replacement = listOf(entry(2))
        var roomPrimary = true
        var markerFails = true
        var markerAttempts = 0
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) original else null }
        doAnswer { throw IOException("Room write failed") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw IOException("Room import unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            markerAttempts++
            if (markerFails) throw IOException("marker write failed")
            roomPrimary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())

        Fixture(room).use { fixture ->
            repeat(2) {
                assertTrue(runCatching { fixture.repository.updateHistoryIfUnchanged(replacement, 7) }.exceptionOrNull() is IOException)
                assertEquals(original, fixture.repository.historyFlow.value)
                Fixture(room).use { restarted -> assertEquals(original, restarted.repository.historyFlow.value) }
            }
            assertEquals(2, markerAttempts)
            assertEquals(7L, fixture.storage.getSyncMutationVersion())
            markerFails = false
            assertTrue(fixture.repository.updateHistoryIfUnchanged(replacement, 7))
            assertEquals(replacement, fixture.repository.historyFlow.value)
            Fixture(room).use { restarted -> assertEquals(replacement, restarted.repository.historyFlow.value) }
        }
    }

    @Test
    fun `failed JSON cannot switch primary and repaired JSON can retry same epoch`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val original = listOf(entry(1))
        `when`(room.readIfRoomPrimary()).thenReturn(original)
        doAnswer { throw IOException("Room write failed") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        val file = File(temporary.root, "play_history.json")
        assertTrue(file.mkdir())
        val blocker = File(file, "blocker").also { it.writeText("blocked") }
        Fixture(room).use { fixture ->
            repeat(2) {
                assertTrue(runCatching { fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7) }.exceptionOrNull() is IOException)
                assertEquals(original, fixture.repository.historyFlow.value)
            }
            verify(room, never()).markLegacyJsonPrimary(anyLong())
            Fixture(room).use { restarted -> assertEquals(original, restarted.repository.historyFlow.value) }
            assertTrue(blocker.delete())
            assertTrue(file.delete())
            assertTrue(fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7))
            verify(room).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `cancelled Room write preserves cancellation and cannot write fallback`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val original = listOf(entry(1))
        val cancellation = CancellationException("cancelled Room write")
        `when`(room.readIfRoomPrimary()).thenReturn(original)
        doAnswer { throw cancellation }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(room).use { fixture ->
            repeat(2) {
                assertCancellation(cancellation, runCatching { fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7) }.exceptionOrNull())
                assertEquals(original, fixture.repository.historyFlow.value)
            }
            assertFalse(File(temporary.root, "play_history.json").exists())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `cancelled fallback marker is not acknowledged and retries without replacing memory`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val original = listOf(entry(1))
        val cancellation = CancellationException("cancelled marker write")
        `when`(room.readIfRoomPrimary()).thenReturn(original)
        doAnswer { throw IOException("Room write failed") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw cancellation }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(room).use { fixture ->
            repeat(2) {
                assertCancellation(cancellation, runCatching { fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7) }.exceptionOrNull())
                assertEquals(original, fixture.repository.historyFlow.value)
            }
        }
    }

    @Test
    fun `successful Room save publishes and persists without legacy fallback`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(1))
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer { primary = it.getArgument(1); Unit }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(room).use { fixture ->
            assertTrue(fixture.repository.updateHistoryIfUnchanged(listOf(entry(2)), 7))
            assertEquals(primary, fixture.repository.historyFlow.value)
            Fixture(room).use { restarted -> assertEquals(primary, restarted.repository.historyFlow.value) }
            assertFalse(File(temporary.root, "play_history.json").exists())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `cancelled ordinary save retries the same Flow and epoch before acknowledging durability`() = runTest {
        val room = mockPlayHistoryRoomStore()
        var primary = listOf(entry(1))
        var roomPrimary = true
        var attempts = 0
        val cancellation = CancellationException("ordinary Room save cancelled")
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) primary else null }
        doAnswer {
            attempts++
            if (attempts == 1) throw cancellation
            val previous = it.getArgument<List<PlayedEntry>>(0)
            val next = it.getArgument<List<PlayedEntry>>(1)
            if (previous != next) primary = next
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        doAnswer { throw IOException("Room import unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer { roomPrimary = false; Unit }.`when`(room).markLegacyJsonPrimary(anyLong())

        Fixture(room).use { fixture ->
            val replacement = listOf(entry(2))
            assertCancellation(cancellation, runCatching { fixture.repository.updateHistory(replacement) }.exceptionOrNull())
            assertEquals(replacement, fixture.repository.historyFlow.value)
            assertEquals(listOf(entry(1)), primary)
            assertFalse(File(temporary.root, "play_history.json").exists())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
            Fixture(room).use { restarted -> assertEquals(primary, restarted.repository.historyFlow.value) }

            assertTrue(runCatching { fixture.repository.syncSnapshot() }.exceptionOrNull() is IOException)
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(fixture.repository.updateHistoryIfUnchanged(fixture.repository.historyFlow.value, 7))
            assertEquals(2, attempts)
            assertEquals(7L, fixture.storage.getSyncMutationVersion())
            Fixture(room).use { restarted -> assertEquals(replacement, restarted.repository.historyFlow.value) }
        }
    }

    @Test
    fun `construction does not wait for the history read and an early write merges into it`() = runTest {
        val room = mockPlayHistoryRoomStore()
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val persisted = CountDownLatch(1)
        val stored = listOf(entry(2), entry(1))
        val writes = mutableListOf<Pair<List<PlayedEntry>, List<PlayedEntry>>>()
        `when`(room.readIfRoomPrimary()).thenAnswer {
            readStarted.countDown()
            releaseRead.await()
            stored
        }
        doAnswer { invocation ->
            writes += invocation.getArgument<List<PlayedEntry>>(0) to invocation.getArgument<List<PlayedEntry>>(1)
            persisted.countDown()
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())

        Fixture(room, awaitInitialLoad = false).use { fixture ->
            assertTrue(readStarted.await(5, TimeUnit.SECONDS))
            assertTrue(fixture.repository.historyFlow.value.isEmpty())
            fixture.repository.updateRememberedPlaybackPosition(entry(3).toSongItem(), 1_000L, now = 3L)
            releaseRead.countDown()

            assertTrue(persisted.await(5, TimeUnit.SECONDS))
            val recent = entry(3).toSongItem().toPlayedEntry(3L).copy(resumePositionMs = 1_000L)
            assertEquals(listOf(stored to listOf(recent) + stored), writes)
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(listOf(recent) + stored, fixture.repository.syncSnapshot())
        }
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue(actual is CancellationException)
        assertEquals(expected.message, actual?.message)
    }

    private fun historyStorageWithDurableDeletions(removeFailure: () -> Exception? = { null }): SecureTokenStorage {
        val storage = mock(SecureTokenStorage::class.java)
        var deletions = emptyList<SyncRecentPlayDeletion>()
        `when`(storage.getRecentPlayDeletions()).thenAnswer { deletions }
        doAnswer {
            deletions = (deletions + it.getArgument<List<SyncRecentPlayDeletion>>(0))
                .distinctBy { deletion -> deletion.songId }
            Unit
        }.`when`(storage).addRecentPlayDeletions(anyList())
        doAnswer {
            removeFailure()?.let { error -> throw error }
            val identity = it.getArgument<SongIdentity>(0)
            deletions = deletions.filterNot { deletion ->
                SongIdentity(deletion.songId, deletion.album, deletion.mediaUri) == identity
            }
            Unit
        }.`when`(storage).removeRecentPlayDeletion(any(SongIdentity::class.java) ?: SongIdentity(0L, "", null))
        doAnswer { deletions = emptyList(); Unit }.`when`(storage).clearAll()
        return storage
    }

    private fun applyHistoryDelta(
        primary: List<PlayedEntry>, previous: List<PlayedEntry>, next: List<PlayedEntry>
    ): List<PlayedEntry> {
        val rows = primary.associateBy { it.id }.toMutableMap()
        val nextIds = next.map { it.id }.toSet()
        previous.filter { it.id !in nextIds }.forEach { rows.remove(it.id) }
        next.filter { it !in previous }.forEach { rows[it.id] = it }
        return rows.values.sortedByDescending { it.playedAt }
    }

    private inner class Fixture(
        room: PlayHistoryRoomStore,
        testScope: TestScope? = null,
        storageOverride: SecureTokenStorage? = null,
        awaitInitialLoad: Boolean = true,
        uncaughtFailures: MutableList<Throwable>? = null
    ) : Closeable {
        val storage = storageOverride ?: mock(SecureTokenStorage::class.java)
        val repository: PlayHistoryRepository
        init {
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.filesDir).thenReturn(temporary.root)
            `when`(storage.getSyncMutationVersion()).thenReturn(7L)
            `when`(storage.getOrCreateDeviceId()).thenReturn("device")
            val constructor = PlayHistoryRepository::class.java.getDeclaredConstructor(Context::class.java, PlayHistoryRoomStore::class.java)
            constructor.isAccessible = true
            repository = constructor.newInstance(context, room)
            if (awaitInitialLoad) runBlocking { repository.awaitInitialLoad() }
            PlayHistoryRepository::class.java.getDeclaredField("storage\$delegate").also { it.isAccessible = true }.set(repository, lazy { storage })
            if (testScope != null) {
                val field = PlayHistoryRepository::class.java.getDeclaredField("scope").also { it.isAccessible = true }
                (field.get(repository) as CoroutineScope).cancel()
                field.set(repository, CoroutineScope(
                    SupervisorJob(testScope.backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(testScope.testScheduler) +
                        if (uncaughtFailures == null) EmptyCoroutineContext else CoroutineExceptionHandler { _, error -> uncaughtFailures += error }
                ))
            }
        }
        override fun close() {
            val field = PlayHistoryRepository::class.java.getDeclaredField("scope").also { it.isAccessible = true }
            (field.get(repository) as CoroutineScope).cancel()
        }
    }

    private fun entry(id: Long) = PlayedEntry(id = id, name = "song $id", artist = "artist", album = "netease",
        albumId = 1L, durationMs = 100L, coverUrl = null, playedAt = id)
}
