package moe.ouom.neriplayer.core.download.execution.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.OperationIdentity
import moe.ouom.neriplayer.data.identity.stableKey
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionRoomCancellationStoreTest {
    private val fixture = DownloadExecutionRoomFixture()
    private val context get() = fixture.context
    private val database get() = fixture.database
    private val store = DownloadExecutionRoomCancellationStore

    @After
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun `operation identity listing pages through every journal row`() = runTest {
        assertEquals(emptyList<OperationIdentity>(), store.listAllOperationIdentities(context, database))

        val songs = (1L..70L).map { id -> testSong(id) }
        songs.forEach { song -> fixture.upsert(request(song, operationId = "op-%03d".format(song.id)), "QUEUED") }

        val identities = store.listAllOperationIdentities(context, database)

        assertEquals(70, identities.size)
        assertEquals("op-001", identities.first().operationId)
        assertEquals(songs.last().stableKey(), identities.last().stableKey)
        assertEquals(identities.map { it.operationId }, store.listAllOperationIds(context, database))
    }

    @Test
    fun `user cancellation lookup returns only rows carrying a user stop credential`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song, operationId = "op-queued"), "QUEUED", createdAtMs = 100L)
        fixture.upsert(request(song, operationId = "op-stopped"), "STOPPED", createdAtMs = 200L)
        fixture.upsert(request(song, operationId = "op-user-stop"), "RUNNING", createdAtMs = 300L)
        fixture.upsert(request(song, operationId = "op-cancel-code"), "RETRYABLE", createdAtMs = 400L)
        fixture.upsert(request(song, operationId = "op-late"), "CANCEL_REQUESTED", createdAtMs = 900L)
        fixture.rewrite("op-user-stop") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-cancel-code") { it.copy(lastErrorCode = "USER_CANCELLED") }

        assertEquals(emptyList<String>(), store.findUserCancellationOperationIdsForSong(context, " ", database))
        assertEquals(
            setOf("op-stopped", "op-user-stop", "op-cancel-code", "op-late"),
            store.findUserCancellationOperationIdsForSong(context, " ${song.stableKey()} ", database).toSet()
        )
        assertEquals(
            setOf("op-stopped", "op-user-stop", "op-cancel-code"),
            store.findUserCancellationOperationIdsForSong(context, song.stableKey(), database, createdAtMsAtMost = 500L)
                .toSet()
        )
    }

    @Test
    fun `cancellation identity listings capture creation time and skip blank keys`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        fixture.upsert(request(first), "QUEUED", createdAtMs = 10L)
        fixture.upsert(request(second), "INVALID", createdAtMs = 20L)
        fixture.upsert(request(testSong(3L)), "COMPLETED", createdAtMs = 30L)

        assertEquals(
            listOf(
                OperationIdentity("op-1", first.stableKey(), 10L),
                OperationIdentity("op-2", second.stableKey(), 20L)
            ),
            store.listCancellationIdentitiesAnyLibrary(context, database)
        )
        assertEquals(emptyList<OperationIdentity>(), store.listOperationIdentitiesForStableKeys(context, listOf(" "), database))
        assertEquals(
            listOf(OperationIdentity("op-1", first.stableKey(), 10L)),
            store.listOperationIdentitiesForStableKeys(
                context,
                listOf(first.stableKey(), second.stableKey(), first.stableKey()),
                database
            )
        )
    }

    @Test
    fun `cancel all marks transfers cancelled and commit boundaries as user stopped`() = runTest {
        val queued = testSong(1L)
        val committed = testSong(2L)
        val stopped = testSong(3L)
        val malformed = testSong(4L)
        val finished = testSong(5L)
        fixture.upsert(request(queued), "QUEUED", queueOrder = 1)
        fixture.upsert(request(committed), "CORE_COMMITTED", queueOrder = 2)
        fixture.upsert(request(stopped), "STOPPED", queueOrder = 3)
        fixture.upsert(request(malformed), "RETRYABLE", queueOrder = 4)
        fixture.upsert(request(finished), "COMPLETED", queueOrder = 5)
        fixture.rewrite("op-3") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-4") { it.copy(sourceHintJson = "not-json") }

        val snapshot = store.requestCancelAll(context, database)

        assertEquals(listOf("op-1", "op-2", "op-3", "op-4"), snapshot.operationIds)
        assertEquals(
            setOf(queued.stableKey(), committed.stableKey(), stopped.stableKey(), malformed.stableKey()),
            snapshot.stableKeys
        )
        assertEquals(listOf("op-1", "op-2", "op-3"), snapshot.entries.map { it.request.operationId })
        assertEquals(listOf("QUEUED", "CORE_COMMITTED", "STOPPED"), snapshot.entries.map { it.state })
        assertEquals("CANCEL_REQUESTED", fixture.state("op-1"))
        assertEquals("CORE_COMMITTED", fixture.state("op-2"))
        assertTrue(fixture.row("op-2").stopRequestedByUser)
        assertEquals("CANCEL_REQUESTED", fixture.state("op-3"))
        assertEquals("INVALID", fixture.state("op-4"))
        assertEquals("COMPLETED", fixture.state("op-5"))
    }

    @Test
    fun `fixed operation cancellation never touches rows outside the snapshot`() = runTest {
        val target = testSong(1L)
        val boundary = testSong(2L)
        val replacement = testSong(3L)
        val malformed = testSong(4L)
        fixture.upsert(request(target), "RUNNING")
        fixture.upsert(request(boundary), "ASSETS_ENRICHING")
        fixture.upsert(request(replacement), "QUEUED")
        fixture.upsert(request(malformed), "QUEUED")
        fixture.upsert(request(testSong(5L)), "COMPLETED")
        fixture.rewrite("op-4") { it.copy(sourceHintJson = "not-json") }

        val empty = store.requestCancelOperations(context, listOf(" ", ""), database)
        assertTrue(empty.entries.isEmpty() && empty.operationIds.isEmpty() && empty.stableKeys.isEmpty())

        val snapshot = store.requestCancelOperations(context, listOf("op-1", " op-2 ", "op-4", "op-5", "op-1"), database)

        assertEquals(setOf("op-1", "op-2", "op-4", "op-5"), snapshot.operationIds.toSet())
        assertEquals(listOf("op-1", "op-2"), snapshot.entries.map { it.request.operationId }.sorted())
        assertEquals("CANCEL_REQUESTED", fixture.state("op-1"))
        assertTrue(fixture.row("op-2").stopRequestedByUser)
        assertEquals("QUEUED", fixture.state("op-3"))
        assertEquals("INVALID", fixture.state("op-4"))
        assertEquals("COMPLETED", fixture.state("op-5"))
    }

    @Test
    fun `fast cancellation paths report how many rows were fenced`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val third = testSong(3L)
        fixture.upsert(request(first), "QUEUED")
        fixture.upsert(request(second), "COMMITTING")
        fixture.upsert(request(third), "RETRYABLE")

        assertEquals(0, store.requestCancelOperationsFast(context, listOf(" "), database))
        assertEquals(2, store.requestCancelOperationsFast(context, listOf("op-1", "op-2", "missing"), database))
        assertEquals("CANCEL_REQUESTED", fixture.state("op-1"))
        assertTrue(fixture.row("op-2").stopRequestedByUser)

        assertEquals(1, store.requestCancelForStableKeysFast(context, listOf(third.stableKey(), first.stableKey()), database))
        assertEquals("CANCEL_REQUESTED", fixture.state("op-3"))

        fixture.upsert(request(testSong(4L)), "RUNNING")
        fixture.upsert(request(testSong(5L)), "DEGRADED_COMPLETE")
        assertEquals(2, store.requestCancelAllFast(context, database))
    }

    @Test
    fun `requested cancellations finalize and cancelled rows can be purged by key or id`() = runTest {
        val first = testSong(1L)
        val second = testSong(2L)
        val third = testSong(3L)
        listOf(first, second, third).forEach { song -> fixture.upsert(request(song), "QUEUED") }
        store.requestCancelOperationsFast(context, listOf("op-1", "op-2", "op-3"), database)

        assertEquals(0, store.finalizeRequestedCancellations(context, listOf(" "), database))
        assertEquals(2, store.finalizeRequestedCancellations(context, listOf("op-1", "op-2"), database))
        assertEquals("CANCELLED", fixture.state("op-1"))
        assertEquals("CANCEL_REQUESTED", fixture.state("op-3"))

        DownloadExecutionRoomStore.purgeCancelled(context, listOf(" "), database)
        assertEquals("CANCELLED", fixture.state("op-1"))
        DownloadExecutionRoomStore.purgeCancelled(context, listOf(first.stableKey()), database)
        assertNull(fixture.state("op-1"))

        assertEquals(0, DownloadExecutionRoomStore.purgeCancelledOperationIds(context, listOf(""), database))
        fixture.upsert(request(testSong(4L)), "QUEUED")
        assertEquals(
            2,
            DownloadExecutionRoomStore.purgeCancelledOperationIds(context, listOf("op-2", "op-3", "op-4"), database)
        )
        assertNull(fixture.state("op-2"))
        assertNull(fixture.state("op-3"))
        assertEquals("QUEUED", fixture.state("op-4"))
    }

    @Test
    fun `state based deletion and terminal pruning remove only matching rows`() = runTest {
        fixture.upsert(request(testSong(1L)), "CANCELLED")
        fixture.upsert(request(testSong(2L)), "CANCEL_REQUESTED")
        fixture.upsert(request(testSong(3L)), "COMPLETED", createdAtMs = 10L)
        fixture.upsert(request(testSong(4L)), "INVALID", createdAtMs = 20L)
        fixture.upsert(request(testSong(5L)), "QUEUED", createdAtMs = 5L)

        store.deleteByStateAndStableKeys(context, "CANCELLED", listOf(" "), database)
        assertEquals("CANCELLED", fixture.state("op-1"))
        DownloadExecutionRoomStore.purgeAllCancelled(context, database)
        assertNull(fixture.state("op-1"))
        assertNull(fixture.state("op-2"))

        assertEquals(0, store.pruneTerminalOperations(context, cutoffMs = Long.MAX_VALUE, limit = 0, database = database))
        assertEquals(1, store.pruneTerminalOperations(context, cutoffMs = 15L, limit = 10, database = database))
        assertNull(fixture.state("op-3"))
        assertEquals("INVALID", fixture.state("op-4"))
        assertEquals("QUEUED", fixture.state("op-5"))
    }

    @Test
    fun `failed progress dismissal stops only failures older than the cutoff`() = runTest {
        val old = testSong(1L)
        val fresh = testSong(2L)
        fixture.upsert(request(old), "INVALID", createdAtMs = 10L)
        fixture.upsert(request(fresh), "INVALID", createdAtMs = 1_000L)

        store.dismissFailedProgressOperations(context, listOf(old.stableKey(), fresh.stableKey(), " "), 500L, database)

        assertTrue(fixture.row("op-1").stopRequestedByUser)
        assertFalse(fixture.row("op-2").stopRequestedByUser)
    }

    @Test
    fun `song lookups prefer the current library and rehome rows from older roots`() = runTest {
        val local = testSong(1L)
        val moved = testSong(2L)
        val stopped = testSong(3L)
        fixture.upsert(request(local), "QUEUED")
        fixture.upsert(request(moved), "RETRYABLE")
        fixture.upsert(request(stopped), "QUEUED")
        fixture.rewrite("op-2") { it.copy(libraryId = "old-root") }
        fixture.rewrite("op-3") { it.copy(libraryId = "old-root", stopRequestedByUser = true) }
        val currentLibrary = fixture.row("op-1").libraryId

        assertNull(store.findOperationIdForSong(context, "  ", database))
        assertEquals("op-1", store.findOperationIdForSong(context, local.stableKey(), database))
        assertEquals("op-2", store.findOperationIdForSong(context, " ${moved.stableKey()} ", database))
        assertEquals(currentLibrary, fixture.row("op-2").libraryId)
        assertNull(store.findOperationIdForSong(context, stopped.stableKey(), database))
        assertEquals("old-root", fixture.row("op-3").libraryId)
    }

    @Test
    fun `cancellation song lookup spans libraries and respects the creation boundary`() = runTest {
        val song = testSong(1L)
        fixture.upsert(request(song, operationId = "op-current"), "QUEUED", createdAtMs = 100L)
        fixture.upsert(request(song, operationId = "op-old-root"), "RUNNING", createdAtMs = 50L)
        fixture.upsert(request(song, operationId = "op-newer"), "QUEUED", createdAtMs = 900L)
        fixture.rewrite("op-old-root") { it.copy(libraryId = "old-root") }
        val currentLibrary = fixture.row("op-current").libraryId

        assertEquals(emptyList<String>(), store.findOperationIdsForSong(context, "", database))
        assertEquals(
            setOf("op-current", "op-old-root"),
            store.findOperationIdsForSong(context, song.stableKey(), database, createdAtMsAtMost = 500L).toSet()
        )
        assertEquals(currentLibrary, fixture.row("op-old-root").libraryId)
        assertEquals(
            setOf("op-current", "op-old-root", "op-newer"),
            store.findOperationIdsForSong(context, song.stableKey(), database).toSet()
        )
    }

    @Test
    fun `readable lookups skip excluded stopped and malformed rows`() = runTest {
        val plain = testSong(1L)
        val userStopped = testSong(2L)
        val userCancelled = testSong(3L)
        val malformed = testSong(4L)
        val moved = testSong(5L)
        val excluded = testSong(6L)
        listOf(plain, userStopped, userCancelled, malformed, moved, excluded).forEach { song ->
            fixture.upsert(request(song), "QUEUED")
        }
        fixture.rewrite("op-2") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-3") { it.copy(stopRequestedByUser = true, lastErrorCode = "USER_CANCELLED") }
        fixture.rewrite("op-4") { it.copy(sourceHintJson = "not-json") }
        fixture.rewrite("op-5") { it.copy(libraryId = "old-root") }
        val keys = listOf(plain, userStopped, userCancelled, malformed, moved, excluded).map { it.stableKey() }
        val states = listOf("QUEUED")

        assertEquals(emptyMap<String, Any>(), store.findReadableOperationsBySongKeys(context, listOf(" "), states, database = database))
        assertEquals(emptyMap<String, Any>(), store.findReadableOperationsBySongKeys(context, keys, emptyList(), database = database))

        val cancelledExcluded = store.findReadableOperationsBySongKeys(
            context = context,
            songKeys = keys + " ",
            states = states,
            excludeUserCancelledStops = true,
            excludedOperationIds = listOf(" op-6 "),
            database = database
        )
        assertEquals(
            mapOf(
                plain.stableKey() to "op-1",
                userStopped.stableKey() to "op-2",
                moved.stableKey() to "op-5"
            ),
            cancelledExcluded.mapValues { (_, request) -> request.operationId }
        )
        assertEquals("INVALID", fixture.state("op-4"))
        assertEquals(fixture.row("op-1").libraryId, fixture.row("op-5").libraryId)

        val stoppedExcluded = store.findReadableOperationsBySongKeys(
            context = context,
            songKeys = keys,
            states = states,
            excludeUserStoppedOperations = true,
            database = database
        )
        assertEquals(setOf("op-1", "op-5", "op-6"), stoppedExcluded.values.map { it.operationId }.toSet())

        assertNull(store.findReadableOperationIdForSong(context, " ", states, database = database))
        assertEquals("op-1", store.findReadableOperationIdForSong(context, plain.stableKey(), states, database = database))
        assertNull(
            store.findReadableOperationIdForSong(
                context,
                userStopped.stableKey(),
                states,
                excludeUserStoppedOperations = true,
                database = database
            )
        )
    }

    @Test
    fun `old root readable rows are skipped when stopped excluded or malformed`() = runTest {
        val stopped = testSong(1L)
        val excluded = testSong(2L)
        val malformed = testSong(3L)
        listOf(stopped, excluded, malformed).forEach { song -> fixture.upsert(request(song), "RETRYABLE") }
        listOf("op-1", "op-2", "op-3").forEach { id -> fixture.rewrite(id) { it.copy(libraryId = "old-root") } }
        fixture.rewrite("op-1") { it.copy(stopRequestedByUser = true) }
        fixture.rewrite("op-3") { it.copy(sourceHintJson = "not-json") }

        val found = store.findReadableOperationsBySongKeys(
            context = context,
            songKeys = listOf(stopped, excluded, malformed).map { it.stableKey() },
            states = listOf("RETRYABLE"),
            excludeUserStoppedOperations = true,
            excludedOperationIds = listOf("op-2"),
            database = database
        )

        assertEquals(emptyMap<String, Any>(), found)
        assertEquals("INVALID", fixture.state("op-3"))
    }

    @Test
    fun `malformed reusable rows are rehydrated from the caller song payload`() = runTest {
        val broken = testSong(1L)
        val healthy = testSong(2L)
        val stopped = testSong(3L)
        val excluded = testSong(4L)
        listOf(broken, healthy, stopped, excluded).forEach { song -> fixture.upsert(request(song), "QUEUED") }
        listOf("op-1", "op-3", "op-4").forEach { id -> fixture.rewrite(id) { it.copy(sourceHintJson = "not-json") } }
        fixture.rewrite("op-3") { it.copy(stopRequestedByUser = true) }

        assertEquals(
            emptySet<String>(),
            store.rehydrateMalformedReusableOperations(context, emptyList(), true, false, 5_000L, database = database)
        )
        val rehydrated = store.rehydrateMalformedReusableOperations(
            context = context,
            songs = listOf(broken, broken, healthy, stopped, excluded, testSong(9L)),
            userInitiated = false,
            requiresWifiNetwork = false,
            updatedAtMs = 5_000L,
            excludedOperationIds = listOf("op-4"),
            database = database
        )

        assertEquals(setOf(broken.stableKey()), rehydrated)
        val restored = fixture.read("op-1")!!
        assertEquals(broken.stableKey(), restored.song.stableKey())
        assertEquals(broken.name, restored.song.name)
        assertFalse(restored.userInitiated)
        assertFalse(restored.requiresWifiNetwork)
        assertEquals(false, DownloadExecutionRoomStore.cachedNetworkPolicy("op-1"))
        assertNull(fixture.read("op-3"))
        assertNull(fixture.read("op-4"))
    }

    @Test
    fun `single song rehydration reports whether the song was recovered`() = runTest {
        val broken = testSong(1L)
        fixture.upsert(request(broken), "RETRYABLE")
        fixture.rewrite("op-1") { it.copy(sourceHintJson = "not-json") }

        assertTrue(store.rehydrateMalformedReusableOperation(context, broken, true, true, 1L, database))
        assertFalse(store.rehydrateMalformedReusableOperation(context, broken, true, true, 2L, database))
    }
}
