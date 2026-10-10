package moe.ouom.neriplayer.data.stats

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.local.database.entity.stats.PlaybackStatsPendingDeltaEntity
import moe.ouom.neriplayer.data.local.database.store.stats.PagedPlaybackSource
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomState
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsRoomStore
import moe.ouom.neriplayer.data.local.database.store.stats.PlaybackStatsSnapshotHarness
import moe.ouom.neriplayer.data.local.database.store.stats.PrimaryPlaybackTables
import moe.ouom.neriplayer.data.local.database.store.stats.SNAPSHOT_ID
import moe.ouom.neriplayer.data.local.database.store.stats.stagedSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlaybackStatsSyncSnapshotApplyTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val harness = PlaybackStatsSnapshotHarness()
    private val room = mock(PlaybackStatsRoomStore::class.java)
    private val source = PagedPlaybackSource.ordered(emptyList(), emptyList(), pageSize = 256)
    private var primaryState: PlaybackStatsRoomState? = PlaybackStatsRoomState(revision = 1, clearedAt = 50, counterEpochStartedAt = 50)
    private var journal: () -> List<PlaybackStatsPendingDeltaEntity> = { emptyList() }

    @Test
    fun `a stale revision is refused before a diff snapshot is opened`() = runTest {
        val repository = initializedRepository(backgroundScope)

        assertFalse(repository.applySyncSnapshot(source, clearedAt = 700, expectedRevision = 2))

        verify(room, never()).beginDiffSnapshot(anyLong())
        assertEquals(50L, repository.statsClearedAtFlow.value)
    }

    @Test
    fun `undrained journal entries block applying a sync snapshot`() = runTest {
        val repository = initializedRepository(backgroundScope)
        journal = { throw IOException("journal busy") }

        val failure = runCatching { repository.applySyncSnapshot(source, clearedAt = 700, expectedRevision = 1) }.exceptionOrNull()

        assertEquals("Playback statistics have pending journal entries", (failure as IOException).message)
        verify(room, never()).beginDiffSnapshot(anyLong())
    }

    @Test
    fun `a vanished primary state aborts the apply before a diff snapshot is opened`() = runTest {
        val repository = initializedRepository(backgroundScope)
        primaryState = null

        val failure = runCatching { repository.applySyncSnapshot(source, clearedAt = 700, expectedRevision = 1) }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        verify(room, never()).beginDiffSnapshot(anyLong())
    }

    @Test
    fun `a committed diff publishes the later of the frozen and requested clears`() = runTest {
        val repository = initializedRepository(backgroundScope)
        openDiff(commits = true)

        assertTrue(repository.applySyncSnapshot(source, clearedAt = 700, expectedRevision = 1))

        assertEquals(700L, repository.statsClearedAtFlow.value)
        assertEquals(true, harness.staged.getSnapshot(SNAPSHOT_ID)?.sealed)
        verify(room).commitDiffSnapshot(SNAPSHOT_ID, 1)
        verify(room).releaseSnapshot(SNAPSHOT_ID)
    }

    @Test
    fun `a diff that loses the commit keeps the observed clear and is released`() = runTest {
        val repository = initializedRepository(backgroundScope)
        openDiff(commits = false)

        assertFalse(repository.applySyncSnapshot(source, clearedAt = 700, expectedRevision = 1))

        assertEquals(50L, repository.statsClearedAtFlow.value)
        verify(room).releaseSnapshot(SNAPSHOT_ID)
    }

    @Test
    fun `a failing commit still releases the diff snapshot`() = runTest {
        val repository = initializedRepository(backgroundScope)
        openDiff(commits = false)
        doAnswer { throw IOException("commit interrupted") }.`when`(room).commitDiffSnapshot(anyString(), anyLong())

        val failure = runCatching { repository.applySyncSnapshot(source, clearedAt = 700, expectedRevision = 1) }.exceptionOrNull()

        assertEquals("commit interrupted", (failure as IOException).message)
        assertEquals(50L, repository.statsClearedAtFlow.value)
        verify(room).releaseSnapshot(SNAPSHOT_ID)
    }

    private suspend fun openDiff(commits: Boolean) {
        val frozen = stagedSnapshot(clearedAt = 50, sealed = false, isDiff = true)
        harness.staged.upsertSnapshot(frozen)
        PrimaryPlaybackTables().serve(harness.primary)
        doReturn(frozen).`when`(room).beginDiffSnapshot(anyLong())
        doReturn(commits).`when`(room).commitDiffSnapshot(anyString(), anyLong())
    }

    private suspend fun initializedRepository(scope: CoroutineScope): PlaybackStatsRepository {
        `when`(room.database).thenReturn(harness.room.database)
        `when`(room.readPrimaryState()).thenAnswer { primaryState }
        `when`(room.pendingDeltas()).thenAnswer { journal() }
        `when`(room.clearedAtFlow).thenReturn(emptyFlow())
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        `when`(context.filesDir).thenReturn(temporaryFolder.root)
        return PlaybackStatsRepository(context, room, scope) { "device" }.also { repository ->
            assertTrue(repository.awaitInitialized())
        }
    }
}
