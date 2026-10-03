@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package moe.ouom.neriplayer.data.playlist.usage

import android.content.Context
import android.content.SharedPreferences
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.store.PlaylistUsageRoomStore
import moe.ouom.neriplayer.data.history.PlayHistoryRepository
import moe.ouom.neriplayer.data.local.playlist.LocalPlaylistRepository
import moe.ouom.neriplayer.data.model.playlist.LocalPlaylist
import moe.ouom.neriplayer.data.model.stats.LocalPlaylistPlaybackSyncSnapshot
import moe.ouom.neriplayer.data.model.stats.UsageEntry
import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaylistUsageDeletion
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken
import moe.ouom.neriplayer.data.model.sync.SyncRecentPlayDeletion
import moe.ouom.neriplayer.data.sync.store.github.SecureTokenStorage
import moe.ouom.neriplayer.data.playlist.favorite.FavoritePlaylistRepository
import moe.ouom.neriplayer.data.sync.host.AndroidSyncSnapshotBuilder
import moe.ouom.neriplayer.data.sync.merge.engine.SyncDataMerger
import moe.ouom.neriplayer.data.sync.merge.host.SyncMergeHost
import moe.ouom.neriplayer.platform.bilibili.skip.BiliVideoSkipRepository
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.MockedConstruction
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*

class PlaylistUsagePersistenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `cancelled row apply preserves durable deletion and reopening cannot acknowledge old counters`() = runTest {
        val preferences = RamDiskPreferences()
        val storage = realStorage(preferences)
        val primary = usagePrimary(listOf(entry()))
        val deletion = SyncPlaylistUsageDeletion("netease:1", listOf(SyncCausalToken("usage-delete:remote", 1)), 2)
        val cancelled = CancellationException("usage row cancelled")
        Fixture(this, primary.room, storage).use { fixture ->
            assertTrue(fixture.repository.awaitInitialized())
            primary.beforeWrite = { throw cancelled }
            assertSame(cancelled, runCatching {
                fixture.repository.applyMergedStatsAndPersist(listOf(remote().copy(lastOpenedAt = Long.MAX_VALUE)), listOf(deletion))
            }.exceptionOrNull())
            assertEquals(listOf(entry()), primary.rows)
            assertEquals(listOf(deletion), realStorage(preferences.restart()).getPlaylistUsageDeletionBarriersConfirmed())
        }
        primary.beforeWrite = {}
        Fixture(this, primary.room, realStorage(preferences.restart())).use { reopened ->
            assertTrue(reopened.repository.awaitInitialized())
            assertTrue(reopened.repository.syncStats().isEmpty())
            assertTrue(primary.rows.isEmpty())
            reopened.repository.applyMergedStatsAndPersist(listOf(remote().copy(lastOpenedAt = Long.MAX_VALUE)))
            assertTrue(reopened.repository.syncStats().isEmpty())
            reopened.repository.recordOpen(1, "fresh", null, 3, source = "netease", now = 1)
            runCurrent()
            assertTrue(reopened.repository.awaitInitialized())
            assertEquals(1, primary.rows.single().openCount)
            assertEquals(deletion.deletionTokens, primary.rows.single().observedDeletionTokens)
        }
    }

    @Test
    fun `checked barrier commit failure blocks both existing and new repository sync acknowledgements`() = runTest {
        val preferences = RamDiskPreferences()
        val storage = realStorage(preferences)
        val primary = usagePrimary(listOf(entry()))
        val deletion = SyncPlaylistUsageDeletion("netease:1", listOf(SyncCausalToken("usage-delete:remote", 1)), 2)
        preferences.failCommits = true
        assertTrue(runCatching { storage.mergePlaylistUsageDeletionBarriers(listOf(deletion)) }.isFailure)
        assertTrue(realStorage(preferences.restart()).getPlaylistUsageDeletionBarriersConfirmed().isEmpty())
        repeat(2) {
            Fixture(this, primary.room, if (it == 0) storage else realStorage(preferences)).use { fixture ->
                assertFalse(fixture.repository.awaitInitialized())
                assertTrue(runCatching { fixture.repository.syncStats() }.isFailure)
                assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.isFailure)
                assertEquals(listOf(entry()), primary.rows)
            }
        }
        preferences.failCommits = false
        Fixture(this, primary.room, realStorage(preferences)).use { fixture ->
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(fixture.repository.syncStats().isEmpty())
            assertTrue(primary.rows.isEmpty())
        }
        assertEquals(listOf(deletion), realStorage(preferences.restart()).getPlaylistUsageDeletionBarriersConfirmed())
    }

    @Test
    fun `synced manual usage deletion prevents stale counters and future clocks from reaching a new device`() = runTest {
        for (lastOpened in listOf(100L, Long.MAX_VALUE)) {
            val storage = realStorage(RamDiskPreferences())
            val primary = usagePrimary(listOf(entry()))
            val stale = remote().copy(lastOpenedAt = lastOpened, openCount = 100_000)
            Fixture(this, primary.room, storage).use { deviceA ->
                assertTrue(deviceA.repository.awaitInitialized())
                deviceA.repository.removeEntry(1, "netease")
                runCurrent()
                assertTrue(deviceA.repository.awaitInitialized())
                assertTrue(primary.rows.isEmpty())

                val local = usageSyncData(storage, deviceA.repository)
                val remote = SyncData(playlistUsageStats = listOf(stale))
                val merged = usageMerger().merge(local, remote, 0L).mergedData
                assertTrue("A deletion must hide B's old counters before publication", merged.playlistUsageStats.isEmpty())

                val freshPrimary = usagePrimary(emptyList())
                Fixture(this, freshPrimary.room, realStorage(RamDiskPreferences())).use { deviceC ->
                    assertTrue(deviceC.repository.awaitInitialized())
                    deviceC.repository.applyMergedStatsAndPersist(merged.playlistUsageStats, merged.playlistUsageDeletions)
                    assertTrue(deviceC.repository.syncStats().isEmpty())
                    assertTrue(freshPrimary.rows.isEmpty())
                    deviceC.repository.applyMergedStatsAndPersist(listOf(stale))
                    assertTrue(deviceC.repository.syncStats().isEmpty())
                    assertEquals(merged.playlistUsageDeletions, deviceC.repository.syncStatsAndDeletions().second)
                }
                val repeated = usageMerger().merge(merged, remote, 0L).mergedData
                assertTrue("A second old-device sync cannot revive the hidden item", repeated.playlistUsageStats.isEmpty())
            }
        }
    }

    @Test
    fun `a real open after observing deletion restores only fresh counters despite a stale future-clock device`() = runTest {
        val storage = realStorage(RamDiskPreferences())
        val primary = usagePrimary(listOf(entry()))
        Fixture(this, primary.room, storage).use { deviceA ->
            assertTrue(deviceA.repository.awaitInitialized())
            deviceA.repository.removeEntry(1, "netease")
            runCurrent()
            assertTrue(deviceA.repository.awaitInitialized())
            assertTrue(primary.rows.isEmpty())

            deviceA.repository.recordOpen(1, "reopened", null, 3, source = "netease", now = 200)
            runCurrent()
            assertTrue(deviceA.repository.awaitInitialized())
            assertEquals(1, primary.rows.single().openCount)
            val restored = usageSyncData(storage, deviceA.repository)
            val stale = SyncData(playlistUsageStats = listOf(remote().copy(lastOpenedAt = Long.MAX_VALUE, openCount = 100_000)))
            val merged = usageMerger().merge(restored, stale, 0L).mergedData
            val visible = merged.playlistUsageStats.single()

            assertEquals("Old hidden totals cannot be added back on restoration", 1, visible.openCount)
            assertEquals(200L, visible.lastOpenedAt)
            val repeated = usageMerger().merge(merged, stale, 0L).mergedData.playlistUsageStats.single()
            assertEquals(visible, repeated)
        }
    }

    @Test
    fun `loaded tombstone cache still confirms shared preference durability at every sync boundary`() = runTest {
        val preferences = RamDiskPreferences()
        val storage = realStorage(preferences)
        val primary = usagePrimary(listOf(entry()))
        Fixture(this, primary.room, storage).use { fixture ->
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(1, fixture.repository.syncStats().size)
            val deletion = SyncRecentPlayDeletion(songId = 7, album = "netease", deletedAt = 300)
            preferences.failCommits = true
            assertTrue(runCatching { storage.addRecentPlayDeletions(listOf(deletion)) }.isFailure)
            assertEquals(listOf(deletion), storage.getRecentPlayDeletions())
            assertTrue(realStorage(preferences.restart()).getRecentPlayDeletions().isEmpty())

            assertFalse(fixture.repository.awaitInitialized())
            assertTrue(runCatching { fixture.repository.syncStats() }.isFailure)
            assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.isFailure)
            assertEquals(listOf(entry()), primary.rows)
            assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)

            preferences.failCommits = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(1, fixture.repository.syncStats().size)
            assertEquals(listOf(deletion), realStorage(preferences.restart()).getRecentPlayDeletions())
        }
    }

    @Test
    fun `ghost tombstone add cannot acknowledge await or snapshot in same or recreated repository`() = runTest {
        val preferences = RamDiskPreferences()
        val storage = realStorage(preferences)
        val primary = usagePrimary(listOf(entry()))
        preferences.failCommits = true
        assertTrue(runCatching { storage.addPlaylistUsageDeletion("netease:1", Long.MAX_VALUE) }.isFailure)
        assertEquals(mapOf("netease:1" to Long.MAX_VALUE), storage.getPlaylistUsageDeletions())
        assertTrue(realStorage(preferences.restart()).getPlaylistUsageDeletions().isEmpty())

        repeat(2) {
            Fixture(this, primary.room, storage).use { fixture ->
                assertFalse(fixture.repository.awaitInitialized())
                assertTrue(runCatching { fixture.repository.syncStats() }.isFailure)
                assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.isFailure)
                assertEquals(listOf(entry()), primary.rows)
            }
        }
        preferences.failCommits = false
        Fixture(this, primary.room, storage).use { fixture ->
            assertTrue(fixture.repository.awaitInitialized())
            fixture.repository.removeEntry(1, "netease")
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(primary.rows.isEmpty())
        }
        val reopenedStorage = realStorage(preferences.restart())
        assertEquals(mapOf("netease:1" to Long.MAX_VALUE), reopenedStorage.getPlaylistUsageDeletions())
        Fixture(this, primary.room, reopenedStorage).use { reopened ->
            reopened.repository.applyMergedStatsAndPersist(listOf(remote()))
            assertTrue(reopened.repository.syncStats().isEmpty())
            assertTrue(primary.rows.isEmpty())
        }
    }

    @Test
    fun `ghost tombstone add cannot short circuit ordinary deletion before durable retry`() = runTest {
        val preferences = RamDiskPreferences()
        val storage = realStorage(preferences)
        val primary = usagePrimary(listOf(entry()))
        preferences.failCommits = true
        assertTrue(runCatching { storage.addPlaylistUsageDeletion("netease:1", Long.MAX_VALUE) }.isFailure)

        repeat(2) {
            Fixture(this, primary.room, storage).use { fixture ->
                fixture.repository.removeEntry(1, "netease")
                runCurrent()
                assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
                assertEquals(listOf(entry()), primary.rows)
                assertFalse(fixture.repository.awaitInitialized())
                assertTrue(runCatching { fixture.repository.syncStats() }.isFailure)
            }
        }
        preferences.failCommits = false
        Fixture(this, primary.room, storage).use { fixture ->
            fixture.repository.removeEntry(1, "netease")
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(primary.rows.isEmpty())
        }
        Fixture(this, primary.room, realStorage(preferences.restart())).use { reopened ->
            reopened.repository.applyMergedStatsAndPersist(listOf(remote()))
            assertTrue(reopened.repository.syncStats().isEmpty())
            assertTrue(primary.rows.isEmpty())
        }
    }

    @Test
    fun `ghost tombstone removal cannot reopen ordinary usage before durable retry`() = runTest {
        val preferences = RamDiskPreferences()
        val storage = realStorage(preferences)
        val primary = usagePrimary(emptyList())
        storage.addPlaylistUsageDeletion("netease:1", Long.MAX_VALUE)
        preferences.failCommits = true
        assertTrue(runCatching { storage.removePlaylistUsageDeletion("netease:1") }.isFailure)
        assertTrue(storage.getPlaylistUsageDeletions().isEmpty())
        assertEquals(mapOf("netease:1" to Long.MAX_VALUE), realStorage(preferences.restart()).getPlaylistUsageDeletions())

        repeat(2) {
            Fixture(this, primary.room, storage).use { fixture ->
                fixture.repository.recordOpen(1, "reopened", null, 3, source = "netease", now = 400)
                runCurrent()
                assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
                assertTrue(primary.rows.isEmpty())
                assertFalse(fixture.repository.awaitInitialized())
                assertTrue(runCatching { fixture.repository.syncStats() }.isFailure)
            }
        }
        preferences.failCommits = false
        Fixture(this, primary.room, storage).use { fixture ->
            fixture.repository.recordOpen(1, "reopened", null, 3, source = "netease", now = 400)
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(1, primary.rows.single().openCount)
        }
        val reopenedStorage = realStorage(preferences.restart())
        assertTrue(reopenedStorage.getPlaylistUsageDeletions().isEmpty())
        Fixture(this, primary.room, reopenedStorage).use { reopened ->
            assertTrue(reopened.repository.awaitInitialized())
            assertEquals(1, reopened.repository.syncStats().single().openCount)
            assertEquals(1, primary.rows.single().openCount)
        }
    }

    @Test
    fun `unreadable Room primary cannot replace usage from a stale JSON snapshot`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        val stale = File(temporary.root, "playlist_usage.json").also { it.writeText(com.google.gson.Gson().toJson(listOf(entry()))) }
        val originalBytes = stale.readBytes()
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary temporarily unavailable") }

        Fixture(this, room).use { fixture ->
            assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
            assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull() is IOException)
            assertTrue(originalBytes.contentEquals(stale.readBytes()))
            verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `usage recovery retries the same primary and retains local only entries`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var unavailable = true
        val complete = listOf(entry(), entry().copy(id = 2))
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("primary unavailable") else complete
        }
        Fixture(this, room).use { fixture ->
            assertFalse(fixture.repository.awaitInitialized())
            assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
            unavailable = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(setOf(1L, 2L), fixture.repository.syncStats().map { it.id }.toSet())
            assertEquals(fixture.repository.frequentPlaylistsFlow.value, fixture.persistedEntries())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
        }
    }

    @Test
    fun `unknown usage rejects ordinary mutations and leaves the primary untouched`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary unavailable") }
        Fixture(this, room).use { fixture ->
            fixture.repository.recordOpen(2, "new", null, 3, source = "netease")
            fixture.repository.updateInfo(2, "new", null, 3, source = "netease")
            fixture.repository.applyMergedStats(listOf(remote()))
            fixture.repository.removeEntry(1, "netease")
            fixture.repository.syncLocalEntries(emptyList())
            fixture.repository.syncLocalArtistEntries(emptyList())
            runCurrent()
            assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
            assertFalse(File(temporary.root, "playlist_usage.json").exists())
            verify(room, never()).writeIncremental(anyList(), anyList(), anyLong())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `usage recovery cancellation propagates without importing a stale snapshot`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenAnswer { throw IOException("primary unavailable") }
        Fixture(this, room).use { fixture ->
            val cancelled = CancellationException("recovery cancelled")
            doAnswer { throw cancelled }.`when`(room).readIfRoomPrimary()
            assertCancellation(cancelled, runCatching { fixture.repository.awaitInitialized() }.exceptionOrNull())
            assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `invalid usage JSON cannot promote empty entries and repair permits retry`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        val file = File(temporary.root, "playlist_usage.json").also { it.writeText("null") }
        Fixture(this, room).use { fixture ->
            assertFalse(fixture.repository.awaitInitialized())
            verify(room, never()).importLegacyAndPromote(anyList(), anyLong())
            assertEquals("null", file.readText())
            file.writeText(com.google.gson.Gson().toJson(listOf(entry())))
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(listOf(1L), fixture.repository.syncStats().map { it.id })
            verify(room).importLegacyAndPromote(org.mockito.ArgumentMatchers.eq(listOf(entry())) ?: emptyList(), anyLong())
        }
    }

    @Test
    fun `ordinary failed usage save blocks sync until latest UI counters are persisted`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var roomPrimary = true
        var markerFails = true
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(entry()) else null }
        failRoomWrites(room)
        doAnswer { throw IOException("import unavailable") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            if (markerFails) throw IOException("marker unavailable")
            roomPrimary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(this, room).use { fixture ->
            fixture.repository.applyMergedStats(listOf(remote()))
            runCurrent()
            val current = fixture.repository.frequentPlaylistsFlow.value
            assertEquals("remote", current.single().name)
            assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
            assertFalse(fixture.repository.awaitInitialized())
            assertEquals(listOf(entry()), fixture.persistedEntries())
            markerFails = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(current, fixture.persistedEntries())
            repeat(2) { assertTrue(fixture.repository.awaitInitialized()) }
            assertEquals(2, fixture.repository.syncStats().single().openCount)
            Fixture(this, room).use { restarted -> assertEquals(current, restarted.repository.frequentPlaylistsFlow.value) }
        }
    }

    @Test
    fun `readiness flush cannot acknowledge UI counters that changed during persistence`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        val uiScheduler = TestCoroutineScheduler()
        Fixture(this, room, uiScheduler = uiScheduler).use { fixture ->
            fixture.repository.applyMergedStats(listOf(remote()))
            var mutated = false
            doAnswer {
                assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
                if (!mutated) {
                    mutated = true
                    fixture.repository.applyMergedStats(listOf(remote().copy(name = "newest UI", lastOpenedAt = 300)))
                }
                Unit
            }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
            assertFalse(fixture.repository.awaitInitialized())
            uiScheduler.runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            uiScheduler.runCurrent()
            assertEquals("newest UI", fixture.repository.syncStats().single().name)
            assertEquals(fixture.repository.frequentPlaylistsFlow.value, fixture.persistedEntries())
        }
    }

    @Test
    fun `committed then cancelled usage sync recovers the actual primary instead of old UI`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var primary = listOf(entry())
        var cancelAfterCommit = true
        val cancelled = CancellationException("committed before cancellation delivery")
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            primary = it.getArgument(1)
            if (cancelAfterCommit) { cancelAfterCommit = false; throw cancelled }
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(this, room).use { fixture ->
            assertCancellation(cancelled, runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote().copy(id = 2, playlistKey = "netease:2"))) }.exceptionOrNull())
            assertEquals(setOf(1L, 2L), primary.map { it.id }.toSet())
            assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
            fixture.repository.applyMergedStats(listOf(remote().copy(id = 3, playlistKey = "netease:3")))
            runCurrent()
            assertEquals(setOf(1L, 2L), primary.map { it.id }.toSet())
            doAnswer {
                fixture.repository.applyMergedStats(listOf(remote().copy(id = 3, playlistKey = "netease:3")))
                primary
            }.`when`(room).readIfRoomPrimary()
            assertTrue(fixture.repository.awaitInitialized())
            runCurrent()
            assertEquals(primary, fixture.repository.frequentPlaylistsFlow.value)
            assertEquals(setOf(1L, 2L), fixture.repository.syncStats().map { it.id }.toSet())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `committed then cancelled usage marker restores JSON records without old UI overwrite`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var roomPrimary = true
        var cancelAfterMarker = true
        val cancelled = CancellationException("marker committed before cancellation delivery")
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(entry()) else null }
        failRoomWrites(room)
        doAnswer {
            roomPrimary = false
            if (cancelAfterMarker) { cancelAfterMarker = false; throw cancelled }
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(this, room).use { fixture ->
            assertCancellation(cancelled, runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote().copy(id = 2, playlistKey = "netease:2"))) }.exceptionOrNull())
            assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(setOf(1L, 2L), fixture.repository.syncStats().map { it.id }.toSet())
            Fixture(this, room).use { restarted -> assertEquals(fixture.repository.frequentPlaylistsFlow.value, restarted.repository.frequentPlaylistsFlow.value) }
        }
    }

    @Test
    fun `ordinary committed usage cancellation retains newer UI while actual primary becomes the delta baseline`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var primary = listOf(entry())
        var unavailable = false
        val previousWrites = mutableListOf<List<UsageEntry>>()
        `when`(room.readIfRoomPrimary()).thenAnswer {
            if (unavailable) throw IOException("authority temporarily unavailable")
            primary
        }
        Fixture(this, room).use { fixture ->
            doAnswer {
                previousWrites += it.getArgument<List<UsageEntry>>(0)
                primary = it.getArgument(1)
                if (previousWrites.size == 1) {
                    fixture.repository.applyMergedStats(listOf(remote().copy(name = "newest UI", lastOpenedAt = 300, openCount = 3)))
                    unavailable = true
                    throw CancellationException("ordinary save committed before cancellation")
                }
                Unit
            }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
            fixture.repository.applyMergedStats(listOf(remote()))
            runCurrent()
            assertEquals("remote", primary.single().name)
            assertEquals("newest UI", fixture.repository.frequentPlaylistsFlow.value.single().name)
            assertFalse(fixture.repository.awaitInitialized())
            assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
            unavailable = false
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals("remote", previousWrites.last().single().name)
            assertEquals("newest UI", primary.single().name)
            assertEquals(3, fixture.repository.syncStats().single().openCount)
            assertEquals(2, previousWrites.size)
            assertEquals(primary, fixture.persistedEntries())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `ordinary remove committed then cancelled retains durable deletion and prevents restored remote rows`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var primary = listOf(entry())
        var writes = 0
        var deletions = emptyMap<String, Long>()
        val storage = mock(SecureTokenStorage::class.java)
        `when`(storage.getPlaylistUsageDeletions()).thenAnswer { deletions }
        `when`(storage.getPlaylistUsageDeletionsConfirmed()).thenAnswer { deletions }
        doAnswer {
            deletions = deletions + (it.getArgument<String>(0) to it.getArgument<Long>(1))
            Unit
        }.`when`(storage).addPlaylistUsageDeletion(anyString(), anyLong())
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        doAnswer {
            val previous = it.getArgument<List<UsageEntry>>(0)
            val next = it.getArgument<List<UsageEntry>>(1)
            val rows = primary.associateBy { row -> row.id }.toMutableMap()
            val nextIds = next.map { row -> row.id }.toSet()
            previous.filter { row -> row.id !in nextIds }.forEach { row -> rows.remove(row.id) }
            next.filter { row -> row !in previous }.forEach { row -> rows[row.id] = row }
            primary = normalizeUsageEntries(rows.values.toList())
            writes++
            if (writes == 1) throw CancellationException("removal committed before cancellation")
            Unit
        }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(this, room, storage).use { fixture ->
            fixture.repository.removeEntry(1, "netease")
            runCurrent()
            assertTrue(primary.isEmpty())
            assertEquals(setOf("netease:1"), deletions.keys)
            assertTrue(deletions.getValue("netease:1") > 0)
            assertTrue(runCatching { fixture.repository.syncStats() }.exceptionOrNull() is IOException)
            assertTrue(fixture.repository.awaitInitialized())
            assertTrue(fixture.repository.syncStats().isEmpty())
            assertEquals(1, writes)
            val retainedDeletions = deletions
            Fixture(this, room, storage).use { reopened ->
                assertTrue(reopened.repository.syncStats().isEmpty())
                reopened.repository.applyMergedStatsAndPersist(listOf(remote()))
                assertTrue(reopened.repository.syncStats().isEmpty())
                assertTrue(primary.isEmpty())
                assertEquals(1, writes)
                reopened.repository.applyMergedStatsAndPersist(listOf(remote().copy(id = 2, playlistKey = "netease:2")))
                assertEquals(listOf(2L), primary.map { it.id })
                assertEquals(retainedDeletions, storage.getPlaylistUsageDeletions())
            }
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `failed marker propagates across same snapshot retry and restart keeps old Room`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var roomPrimary = true
        var markerFails = true
        var attempts = 0
        `when`(room.readIfRoomPrimary()).thenAnswer { if (roomPrimary) listOf(entry()) else null }
        failRoomWrites(room)
        doAnswer { throw IOException("import failed") }.`when`(room).importLegacyAndPromote(anyList(), anyLong())
        doAnswer {
            attempts++
            if (markerFails) throw IOException("marker failed")
            roomPrimary = false
            Unit
        }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(this, room).use { fixture ->
            repeat(2) {
                assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull() is IOException)
                assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
                assertEquals(listOf(entry()), fixture.persistedEntries())
                Fixture(this, room).use { restarted -> assertEquals(listOf(entry()), restarted.repository.frequentPlaylistsFlow.value) }
            }
            assertEquals(2, attempts)
            markerFails = false
            fixture.repository.applyMergedStatsAndPersist(listOf(remote()))
            assertEquals("remote", fixture.repository.frequentPlaylistsFlow.value.single().name)
            Fixture(this, room).use { restarted ->
                assertEquals(fixture.repository.frequentPlaylistsFlow.value, restarted.repository.frequentPlaylistsFlow.value)
            }
        }
    }

    @Test
    fun `JSON failure never switches primary or advances persisted entries`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        failRoomWrites(room)
        val file = File(temporary.root, "playlist_usage.json")
        assertTrue(file.mkdir())
        val blocker = File(file, "blocker").also { it.writeText("blocked") }
        Fixture(this, room).use { fixture ->
            repeat(2) {
                assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull() is IOException)
                assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
                assertEquals(listOf(entry()), fixture.persistedEntries())
            }
            verify(room, never()).markLegacyJsonPrimary(anyLong())
            Fixture(this, room).use { restarted -> assertEquals(listOf(entry()), restarted.repository.frequentPlaylistsFlow.value) }
            assertTrue(blocker.delete())
            assertTrue(file.delete())
            fixture.repository.applyMergedStatsAndPersist(listOf(remote()))
            verify(room).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `Room and marker cancellation propagate without falsely completing sync`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        val cancellation = CancellationException("cancelled")
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        doAnswer { throw cancellation }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
        Fixture(this, room).use { fixture ->
            assertCancellation(cancellation, runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull())
            assertFalse(File(temporary.root, "playlist_usage.json").exists())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
            failRoomWrites(room)
            doAnswer { throw cancellation }.`when`(room).markLegacyJsonPrimary(anyLong())
            repeat(2) {
                assertCancellation(cancellation, runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull())
                assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
                assertEquals(listOf(entry()), fixture.persistedEntries())
            }
        }
    }

    @Test
    fun `successful Room apply waits for persistence before publishing`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var primary = listOf(entry())
        `when`(room.readIfRoomPrimary()).thenAnswer { primary }
        Fixture(this, room).use { fixture ->
            doAnswer {
                assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
                primary = it.getArgument(1)
                Unit
            }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
            fixture.repository.applyMergedStatsAndPersist(listOf(remote()))
            assertEquals(primary, fixture.repository.frequentPlaylistsFlow.value)
            assertEquals(primary, fixture.persistedEntries())
            Fixture(this, room).use { restarted -> assertEquals(primary, restarted.repository.frequentPlaylistsFlow.value) }
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `UI mutation during sync save is retained and prevents acknowledgement`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        Fixture(this, room).use { fixture ->
            doAnswer {
                fixture.repository.applyMergedStats(listOf(remote().copy(name = "new local", lastOpenedAt = 300)))
                Unit
            }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
            assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull() is IOException)
            assertEquals("new local", fixture.repository.frequentPlaylistsFlow.value.single().name)
            doAnswer { Unit }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
            runCurrent()
            assertEquals(fixture.repository.frequentPlaylistsFlow.value, fixture.persistedEntries())
        }
    }

    @Test
    fun `an open during a committed sync save keeps remote counters and the new local open`() = runTest {
        for (cancelAfterCommit in listOf(false, true)) {
            val primary = usagePrimary(listOf(entry()))
            val remoteShard = SyncPlaybackCounterShard("remote-device", 0, playCount = 4, firstPlayedAt = 100, lastPlayedAt = 200)
            val remoteUsage = remote().copy(openCount = 10, counterBaseOpenCount = 6, counterShards = listOf(remoteShard))
            val remoteOnly = remote().copy(playlistKey = "netease:2", id = 2, name = "remote only", openCount = 7)
            val storage = realStorage(RamDiskPreferences())
            val cancelled = CancellationException("sync usage committed before cancellation")
            Fixture(this, primary.room, storage).use { fixture ->
                primary.beforeWrite = {
                    primary.beforeWrite = {}
                    fixture.repository.recordOpen(1, "opened locally", "https://fixture.invalid/local.jpg", 5,
                        source = "netease", now = 300)
                }
                if (cancelAfterCommit) primary.afterWrite = {
                    primary.afterWrite = {}
                    throw cancelled
                }
                val failure = runCatching {
                    fixture.repository.applyMergedStatsAndPersist(listOf(remoteUsage, remoteOnly))
                }.exceptionOrNull()
                if (cancelAfterCommit) assertSame(cancelled, failure) else assertTrue(failure is IOException)
                runCurrent()
                assertTrue(fixture.repository.awaitInitialized())
                val accepted = primary.rows.single { it.id == 1L }
                assertEquals(11, accepted.openCount)
                assertEquals(6L, accepted.counterBaseOpenCount)
                assertEquals(remoteShard, accepted.counterShards.single { it.deviceId == "remote-device" })
                assertEquals(1, accepted.counterShards.single { it.deviceId == "usage-fixture-device" }.playCount)
                assertEquals(300L, accepted.lastOpened)
                assertEquals("opened locally", accepted.name)
                assertEquals("https://fixture.invalid/local.jpg", accepted.picUrl)
                assertEquals(5, accepted.trackCount)
                assertEquals(7, primary.rows.single { it.id == 2L }.openCount)
                assertEquals(primary.rows, fixture.repository.frequentPlaylistsFlow.value)
                Fixture(this, primary.room, storage).use { reopened ->
                    assertEquals(primary.rows, reopened.repository.frequentPlaylistsFlow.value)
                }
            }
        }
    }

    @Test
    fun `an info update during a committed sync save keeps remote counters and the new local metadata`() = runTest {
        val primary = usagePrimary(listOf(entry()))
        val remoteShard = SyncPlaybackCounterShard("remote-device", 0, playCount = 4, firstPlayedAt = 100, lastPlayedAt = 200)
        val remoteUsage = remote().copy(openCount = 10, counterBaseOpenCount = 6, counterShards = listOf(remoteShard))
        Fixture(this, primary.room).use { fixture ->
            primary.beforeWrite = {
                primary.beforeWrite = {}
                fixture.repository.updateInfo(1, "refreshed locally", "https://fixture.invalid/refreshed.jpg", 8,
                    source = "netease", subtitle = "local subtitle", now = 300)
            }
            assertTrue(runCatching {
                fixture.repository.applyMergedStatsAndPersist(listOf(remoteUsage))
            }.exceptionOrNull() is IOException)
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            val accepted = primary.rows.single()
            assertEquals(10, accepted.openCount)
            assertEquals(6L, accepted.counterBaseOpenCount)
            assertEquals(listOf(remoteShard), accepted.counterShards)
            assertEquals(200L, accepted.lastOpened)
            assertEquals("refreshed locally", accepted.name)
            assertEquals("https://fixture.invalid/refreshed.jpg", accepted.picUrl)
            assertEquals(8, accepted.trackCount)
            assertEquals("local subtitle", accepted.subtitle)
            assertEquals(primary.rows, fixture.repository.frequentPlaylistsFlow.value)
            Fixture(this, primary.room).use { reopened ->
                assertEquals(primary.rows, reopened.repository.frequentPlaylistsFlow.value)
            }
        }
    }

    @Test
    fun `an open during sync adds its local delta above a larger same device counter`() = runTest {
        for (cancelAfterCommit in listOf(false, true)) {
            val localShard = SyncPlaybackCounterShard("usage-fixture-device", 0, playCount = 2, firstPlayedAt = 100, lastPlayedAt = 100)
            val initial = entry().copy(openCount = 2, counterShards = listOf(localShard))
            val primary = usagePrimary(listOf(initial))
            val storage = realStorage(RamDiskPreferences())
            val remoteUsage = remote().copy(openCount = 10, counterShards = listOf(localShard.copy(playCount = 10, lastPlayedAt = 200)))
            val cancelled = CancellationException("larger counter committed before cancellation")
            Fixture(this, primary.room, storage).use { fixture ->
                primary.beforeWrite = {
                    primary.beforeWrite = {}
                    fixture.repository.recordOpen(1, "new local open", null, 3, source = "netease", now = 300)
                }
                if (cancelAfterCommit) primary.afterWrite = {
                    primary.afterWrite = {}
                    throw cancelled
                }
                val failure = runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remoteUsage)) }.exceptionOrNull()
                if (cancelAfterCommit) assertSame(cancelled, failure) else assertTrue(failure is IOException)
                runCurrent()
                repeat(2) { assertTrue(fixture.repository.awaitInitialized()) }
                assertEquals(11, primary.rows.single().openCount)
                assertEquals(11, primary.rows.single().counterShards.single().playCount)
                assertEquals(300L, primary.rows.single().lastOpened)
                Fixture(this, primary.room, storage).use { reopened ->
                    assertEquals(11, reopened.repository.syncStats().single().openCount)
                }
            }
        }
    }

    @Test
    fun `an equal timestamp info update rebases only changed fields onto committed remote metadata`() = runTest {
        val initial = entry().copy(picUrl = "https://fixture.invalid/old.jpg", subtitle = "old subtitle")
        val primary = usagePrimary(listOf(initial))
        val remoteUsage = remote().copy(lastOpenedAt = 100, trackCount = 8, coverUrl = "https://fixture.invalid/remote.jpg", subtitle = "remote subtitle")
        Fixture(this, primary.room).use { fixture ->
            primary.beforeWrite = {
                primary.beforeWrite = {}
                fixture.repository.updateInfo(1, "changed locally", null, 3, source = "netease", subtitle = "old subtitle", now = 300)
            }
            assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remoteUsage)) }.exceptionOrNull() is IOException)
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            val accepted = primary.rows.single()
            assertEquals("changed locally", accepted.name)
            assertEquals(8, accepted.trackCount)
            assertEquals("https://fixture.invalid/remote.jpg", accepted.picUrl)
            assertEquals("remote subtitle", accepted.subtitle)
            assertEquals(2, accepted.openCount)
            Fixture(this, primary.room).use { reopened ->
                assertEquals(accepted, reopened.repository.frequentPlaylistsFlow.value.single())
            }
        }
    }

    @Test
    fun `an empty playlist during sync stays removed while remote only usage remains`() = runTest {
        val primary = usagePrimary(listOf(entry()))
        val remoteOnly = remote().copy(playlistKey = "netease:2", id = 2, name = "remote only", openCount = 7)
        Fixture(this, primary.room).use { fixture ->
            primary.beforeWrite = {
                primary.beforeWrite = {}
                fixture.repository.updateInfo(1, "now empty", null, 0, source = "netease", now = 300)
            }
            assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote(), remoteOnly)) }.exceptionOrNull() is IOException)
            runCurrent()
            assertTrue(fixture.repository.awaitInitialized())
            assertEquals(listOf(2L), primary.rows.map { it.id })
            assertEquals(7, primary.rows.single().openCount)
            assertEquals(primary.rows, fixture.repository.frequentPlaylistsFlow.value)
            Fixture(this, primary.room).use { reopened ->
                assertEquals(primary.rows, reopened.repository.frequentPlaylistsFlow.value)
            }
        }
    }

    @Test
    fun `ordinary asynchronous apply keeps immediate UI and logs failed marker without advancing baseline`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        failRoomWrites(room)
        doAnswer { throw IOException("marker failed") }.`when`(room).markLegacyJsonPrimary(anyLong())
        Fixture(this, room).use { fixture ->
            fixture.repository.applyMergedStats(listOf(remote()))
            assertEquals("remote", fixture.repository.frequentPlaylistsFlow.value.single().name)
            runCurrent()
            assertEquals(listOf(entry()), fixture.persistedEntries())
            assertTrue(runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) }.exceptionOrNull() is IOException)
            assertEquals(listOf(entry()), fixture.persistedEntries())
        }
    }

    @Test
    fun `sync waiting for persistence cannot acknowledge after a newer UI generation`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        Fixture(this, room).use { fixture ->
            val mutex = PlaylistUsageRepository::class.java.getDeclaredField("persistenceMutex")
                .also { it.isAccessible = true }.get(fixture.repository) as Mutex
            mutex.lock()
            val pending = async { runCatching { fixture.repository.applyMergedStatsAndPersist(listOf(remote())) } }
            runCurrent()
            fixture.repository.applyMergedStats(listOf(remote().copy(name = "newer UI", lastOpenedAt = 300)))
            mutex.unlock()
            runCurrent()
            assertTrue(pending.await().exceptionOrNull() is IOException)
            assertEquals("newer UI", fixture.repository.frequentPlaylistsFlow.value.single().name)
            assertEquals(fixture.repository.frequentPlaylistsFlow.value, fixture.persistedEntries())
            verify(room, never()).markLegacyJsonPrimary(anyLong())
        }
    }

    @Test
    fun `older queued UI save cannot replace a newer successful sync`() = runTest {
        val room = mock(PlaylistUsageRoomStore::class.java)
        `when`(room.readIfRoomPrimary()).thenReturn(listOf(entry()))
        Fixture(this, room).use { fixture ->
            fixture.repository.applyMergedStats(listOf(remote().copy(name = "queued UI", lastOpenedAt = 150)))
            fixture.repository.applyMergedStatsAndPersist(listOf(remote()))
            runCurrent()
            assertEquals("remote", fixture.repository.frequentPlaylistsFlow.value.single().name)
            assertEquals(fixture.repository.frequentPlaylistsFlow.value, fixture.persistedEntries())
            verify(room, times(1)).writeIncremental(anyList(), anyList(), anyLong())
        }
    }

    @Test
    fun `tombstone read I O failure refuses sync apply and retry uses the actual durable deletions`() = runTest {
        assertTombstoneReadRecovery(IOException("tombstone read failed"))
    }

    @Test
    fun `tombstone read cancellation propagates and retry uses the actual durable deletions`() = runTest {
        assertTombstoneReadRecovery(CancellationException("tombstone read cancelled"))
    }

    @Test
    fun `tombstone add I O failure cannot delete primary rows or poison the retry cache`() = runTest {
        assertTombstoneAddRecovery(IOException("tombstone add failed"))
    }

    @Test
    fun `tombstone add cancellation cannot delete primary rows or poison the retry cache`() = runTest {
        assertTombstoneAddRecovery(CancellationException("tombstone add cancelled"))
    }

    @Test
    fun `tombstone removal I O failure refuses reopening a deleted entry until durable retry`() = runTest {
        assertTombstoneRemovalRecovery(IOException("tombstone removal failed"))
    }

    @Test
    fun `tombstone removal cancellation refuses reopening a deleted entry until durable retry`() = runTest {
        assertTombstoneRemovalRecovery(CancellationException("tombstone removal cancelled"))
    }

    private suspend fun TestScope.assertTombstoneReadRecovery(error: Exception) {
        val primary = usagePrimary(emptyList())
        val deletions = DurableTombstones(mapOf("netease:1" to 300L)).also { it.readFailure = error }
        Fixture(this, primary.room, deletions.storage).use { fixture ->
            repeat(2) {
                assertStorageFailure(error, runCatching {
                    fixture.repository.applyMergedStatsAndPersist(listOf(remote()))
                }.exceptionOrNull())
                runCurrent()
                assertTrue(primary.rows.isEmpty())
                assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
                assertEquals(mapOf("netease:1" to 300L), deletions.durable)
            }
            deletions.readFailure = null
            assertTrue(fixture.repository.awaitInitialized())
            fixture.repository.applyMergedStatsAndPersist(listOf(remote()))
            assertTrue(primary.rows.isEmpty())
            assertTrue(fixture.repository.syncStats().isEmpty())
            Fixture(this, primary.room, deletions.storage).use { reopened ->
                reopened.repository.applyMergedStatsAndPersist(listOf(remote()))
                assertTrue(reopened.repository.syncStats().isEmpty())
                assertTrue(primary.rows.isEmpty())
            }
        }
    }

    private suspend fun TestScope.assertTombstoneAddRecovery(error: Exception) {
        val primary = usagePrimary(listOf(entry()))
        val deletions = DurableTombstones(emptyMap()).also { it.addFailure = error }
        SyncMutationProbe().use { probe ->
            confirmSyncMutationProbe(probe)
            Fixture(this, primary.room, deletions.storage).use { fixture ->
                repeat(2) {
                    val failure = runCatching {
                        fixture.repository.removeEntry(1, "netease")
                    }.exceptionOrNull()
                    if (error is CancellationException) assertStorageFailure(error, failure)
                    else assertNull(failure)
                    runCurrent()
                    assertEquals(listOf(entry()), primary.rows)
                    assertEquals(listOf(entry()), fixture.repository.frequentPlaylistsFlow.value)
                    assertTrue(deletions.durable.isEmpty())
                    assertFalse(File(temporary.root, "playlist_usage.json").exists())
                    assertEquals(0, probe.mutations)
                }
                deletions.addFailure = null
                primary.beforeWrite = { next ->
                    if (next.isEmpty()) assertTrue(deletions.durable.getValue("netease:1") > 0L)
                }
                fixture.repository.removeEntry(1, "netease")
                assertTrue("Successful removal must activate the sync mutation probe", probe.mutations > 0)
                assertTrue(fixture.repository.awaitInitialized())
                runCurrent()
                assertTrue(primary.rows.isEmpty())
                assertTrue(fixture.repository.syncStats().isEmpty())
                val retainedDeletions = deletions.durable
                Fixture(this, primary.room, deletions.storage).use { reopened ->
                    reopened.repository.applyMergedStatsAndPersist(listOf(remote()))
                    assertTrue(reopened.repository.syncStats().isEmpty())
                    assertTrue(primary.rows.isEmpty())
                    assertEquals(retainedDeletions, deletions.durable)
                }
            }
        }
    }

    private suspend fun TestScope.assertTombstoneRemovalRecovery(error: Exception) {
        val primary = usagePrimary(emptyList())
        val originalDeletions = mapOf("netease:1" to 300L)
        val deletions = DurableTombstones(originalDeletions).also { it.removeFailure = error }
        SyncMutationProbe().use { probe ->
            confirmSyncMutationProbe(probe)
            Fixture(this, primary.room, deletions.storage).use { fixture ->
                repeat(2) {
                    val failure = runCatching {
                        fixture.repository.recordOpen(1, "reopened", null, 3, source = "netease", now = 400)
                    }.exceptionOrNull()
                    if (error is CancellationException) assertStorageFailure(error, failure)
                    else assertNull(failure)
                    runCurrent()
                    assertTrue(primary.rows.isEmpty())
                    assertTrue(fixture.repository.frequentPlaylistsFlow.value.isEmpty())
                    assertEquals(originalDeletions, deletions.durable)
                    assertEquals(0, probe.mutations)
                }
                deletions.removeFailure = null
                primary.beforeWrite = { next ->
                    if (next.isNotEmpty()) assertTrue(deletions.durable.isEmpty())
                }
                fixture.repository.recordOpen(1, "reopened", null, 3, source = "netease", now = 400)
                assertTrue("Successful reopen must activate the sync mutation probe", probe.mutations > 0)
                assertTrue(fixture.repository.awaitInitialized())
                runCurrent()
                assertTrue(deletions.durable.isEmpty())
                assertEquals(1L, primary.rows.single().id)
                assertEquals(400L, primary.rows.single().lastOpened)
                assertEquals(1, primary.rows.single().openCount)
                Fixture(this, primary.room, deletions.storage).use { reopened ->
                    assertEquals(400L, reopened.repository.syncStats().single().lastOpenedAt)
                    assertEquals(1, reopened.repository.syncStats().single().openCount)
                }
            }
        }
    }

    private suspend fun TestScope.confirmSyncMutationProbe(probe: SyncMutationProbe) {
        val primary = usagePrimary(emptyList())
        val deletions = DurableTombstones(emptyMap())
        Fixture(this, primary.room, deletions.storage).use { control ->
            control.repository.recordOpen(2, "probe control", null, 3, source = "netease", now = 400)
            assertTrue("Successful control must activate the sync mutation probe", probe.mutations > 0)
            assertTrue(control.repository.awaitInitialized())
            runCurrent()
            assertEquals(2L, primary.rows.single().id)
        }
        probe.mutations = 0
    }

    private class SyncMutationProbe : Closeable {
        var mutations = 0
        private val construction: MockedConstruction<SecureTokenStorage> = mockConstruction(SecureTokenStorage::class.java) { storage, _ ->
            doAnswer { mutations++; mutations.toLong() }.`when`(storage).markSyncMutation()
            `when`(storage.isConfigured()).thenReturn(false)
        }

        override fun close() = construction.close()
    }

    private fun assertStorageFailure(expected: Exception, actual: Throwable?) {
        assertTrue("Expected ${expected.javaClass.simpleName}, got $actual", expected.javaClass.isInstance(actual))
        assertEquals(expected.message, actual?.message)
    }

    private class DurableTombstones(initial: Map<String, Long>) {
        val storage = mock(SecureTokenStorage::class.java)
        var durable = initial.toMap()
        var readFailure: Exception? = null
        var addFailure: Exception? = null
        var removeFailure: Exception? = null

        init {
            `when`(storage.getOrCreateDeviceId()).thenReturn("durable-device")
            `when`(storage.getPlaylistUsageDeletions()).thenAnswer {
                readFailure?.let { throw it }
                durable.toMap()
            }
            `when`(storage.getPlaylistUsageDeletionsConfirmed()).thenAnswer {
                readFailure?.let { throw it }
                durable.toMap()
            }
            doAnswer {
                addFailure?.let { error -> throw error }
                val key = it.getArgument<String>(0)
                val timestamp = it.getArgument<Long>(1)
                durable = durable + (key to maxOf(durable[key] ?: 0L, timestamp))
                Unit
            }.`when`(storage).addPlaylistUsageDeletion(anyString(), anyLong())
            doAnswer {
                removeFailure?.let { error -> throw error }
                durable = durable - it.getArgument<String>(0)
                Unit
            }.`when`(storage).removePlaylistUsageDeletion(anyString(), anyBoolean())
        }
    }

    private fun usageMerger(): SyncDataMerger {
        val host = mock(SyncMergeHost::class.java)
        `when`(host.mergeSuccessMessage).thenReturn("merged")
        `when`(host.initialUploadMessage).thenReturn("initial")
        return SyncDataMerger(host) { 999L }
    }

    private fun usageSyncData(storage: SecureTokenStorage, usage: PlaylistUsageRepository): SyncData {
        val context = mock(Context::class.java)
        val playlists = mock(LocalPlaylistRepository::class.java)
        val favorites = mock(FavoritePlaylistRepository::class.java)
        val history = mock(PlayHistoryRepository::class.java)
        val localStats = mock(LocalPlaylistPlaybackStatsRepository::class.java)
        val skip = mock(BiliVideoSkipRepository::class.java)
        `when`(playlists.playlists).thenReturn(MutableStateFlow<List<LocalPlaylist>>(emptyList()))
        `when`(favorites.getSyncSnapshots()).thenReturn(emptyList())
        `when`(history.syncSnapshot()).thenReturn(emptyList())
        `when`(localStats.syncSnapshot()).thenReturn(LocalPlaylistPlaybackSyncSnapshot(emptyList(), emptyList()))
        `when`(skip.snapshot()).thenReturn(emptyList())
        return AndroidSyncSnapshotBuilder(storage, playlists, favorites, history, usage, localStats, skip).build(context, 0L)
    }

    private fun realStorage(preferences: RamDiskPreferences): SecureTokenStorage {
        val constructor = SecureTokenStorage::class.java.getDeclaredConstructor(
            SharedPreferences::class.java, File::class.java, Function1::class.java
        )
        return constructor.newInstance(preferences.preferences, null, null)
    }

    private class RamDiskPreferences(initial: Map<String, Any> = mapOf("device_id" to "usage-fixture-device")) {
        private val values = initial.toMutableMap()
        private val durableValues = initial.toMutableMap()
        var failCommits = false

        fun restart() = RamDiskPreferences(durableValues.toMap())

        val preferences: SharedPreferences = mock(SharedPreferences::class.java) { call ->
            val key = call.arguments.firstOrNull() as? String
            when (call.method.name) {
                "getString", "getLong", "getBoolean" -> values[key] ?: call.arguments[1]
                "contains" -> values.containsKey(key)
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> null
            }
        }

        private fun editor(): SharedPreferences.Editor {
            val changes = linkedMapOf<String, Any?>()
            var clear = false
            return mock(SharedPreferences.Editor::class.java) { call ->
                when (call.method.name) {
                    "putString", "putLong", "putBoolean" -> {
                        changes[call.arguments[0] as String] = call.arguments[1]
                        call.mock
                    }
                    "remove" -> { changes[call.arguments[0] as String] = null; call.mock }
                    "clear" -> { clear = true; call.mock }
                    "commit", "apply" -> {
                        // commit 先更新 RAM，即使写磁盘失败，后续 getter 也会读到新值
                        if (clear) values.clear()
                        changes.forEach { (key, value) ->
                            if (value == null) values.remove(key) else values[key] = value
                        }
                        changes.clear()
                        clear = false
                        if (!failCommits) {
                            durableValues.clear()
                            durableValues.putAll(values)
                        }
                        if (call.method.name == "commit") !failCommits else null
                    }
                    else -> call.mock
                }
            }
        }
    }

    private class UsagePrimary(initial: List<UsageEntry>) {
        val room = mock(PlaylistUsageRoomStore::class.java)
        var rows = initial.toList()
        var beforeWrite: (List<UsageEntry>) -> Unit = {}
        var afterWrite: () -> Unit = {}
    }

    private suspend fun usagePrimary(initial: List<UsageEntry>): UsagePrimary {
        val primary = UsagePrimary(initial)
        `when`(primary.room.readIfRoomPrimary()).thenAnswer { primary.rows }
        doAnswer {
            val previous = it.getArgument<List<UsageEntry>>(0)
            val next = it.getArgument<List<UsageEntry>>(1)
            assertEquals(primary.rows, previous)
            primary.beforeWrite(next)
            primary.rows = next.toList()
            primary.afterWrite()
            Unit
        }.`when`(primary.room).writeIncremental(anyList(), anyList(), anyLong())
        return primary
    }

    private fun assertCancellation(expected: CancellationException, actual: Throwable?) {
        assertTrue(actual is CancellationException)
        assertEquals(expected.message, actual?.message)
    }

    private suspend fun failRoomWrites(room: PlaylistUsageRoomStore) {
        doAnswer { throw IOException("Room write failed") }.`when`(room).writeIncremental(anyList(), anyList(), anyLong())
    }

    private inner class Fixture(
        testScope: TestScope,
        room: PlaylistUsageRoomStore,
        storageOverride: SecureTokenStorage? = null,
        uiScheduler: TestCoroutineScheduler = testScope.testScheduler
    ) : Closeable {
        private val ownedScope = CoroutineScope(SupervisorJob(testScope.backgroundScope.coroutineContext[Job]) + StandardTestDispatcher(uiScheduler))
        val repository: PlaylistUsageRepository
        init {
            val context = mock(Context::class.java)
            `when`(context.applicationContext).thenReturn(context)
            `when`(context.filesDir).thenReturn(temporary.root)
            val storage = storageOverride ?: mock(SecureTokenStorage::class.java).also {
                `when`(it.getPlaylistUsageDeletions()).thenReturn(emptyMap())
                `when`(it.getPlaylistUsageDeletionsConfirmed()).thenReturn(emptyMap())
            }
            repository = PlaylistUsageRepository(context, room)
            val scope = PlaylistUsageRepository::class.java.getDeclaredField("scope").also { it.isAccessible = true }
            (scope.get(repository) as CoroutineScope).cancel()
            scope.set(repository, ownedScope)
            PlaylistUsageRepository::class.java.getDeclaredField("syncStorage\$delegate").also { it.isAccessible = true }.set(repository, lazy { storage })
        }
        fun persistedEntries(): Any = checkNotNull(PlaylistUsageRepository::class.java.getDeclaredField("persistedEntries")
            .also { it.isAccessible = true }.get(repository))
        override fun close() = ownedScope.cancel()
    }

    private fun entry() = UsageEntry(1, "original", null, 3, "netease", 100, 1)
    private fun remote() = SyncPlaylistUsageStat("netease:1", "netease", 1, name = "remote", trackCount = 3, lastOpenedAt = 200, firstOpenedAt = 100, openCount = 2)
}
