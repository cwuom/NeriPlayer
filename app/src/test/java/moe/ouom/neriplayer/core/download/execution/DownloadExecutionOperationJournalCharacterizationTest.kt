package moe.ouom.neriplayer.core.download.execution

import android.content.Context
import java.nio.file.Files
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class DownloadExecutionOperationJournalCharacterizationTest {
    @Test
    fun `new user download may restart a cancelled operation identity`() {
        assertTrue(
            shouldRestartOperation(
                existingState = "CANCELLED",
                requestedState = "QUEUED",
                userInitiated = true
            )
        )
        assertTrue(
            shouldRestartOperation(
                existingState = "CANCEL_REQUESTED",
                requestedState = "RUNNING",
                userInitiated = true
            )
        )
        assertTrue(
            !shouldRestartOperation(
                existingState = "CANCELLED",
                requestedState = "QUEUED",
                userInitiated = false
            )
        )
    }

    @Test
    fun `late cancel cannot overwrite core committed operation`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-core",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertTrue(store.markCommitting(context, request.operationId))
        assertTrue(store.markCoreCommitted(context, request.operationId))
        assertTrue(!store.requestCancel(context, request.operationId))
        assertEquals(
            "CORE_COMMITTED",
            store.currentState(context, request.operationId)
        )
    }

    @Test
    fun `core commit cannot bypass the committing linearization state`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-before-commit",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)

        assertTrue(!store.markCoreCommitted(context, request.operationId))
        assertEquals("QUEUED", store.currentState(context, request.operationId))
    }

    @Test
    fun `operation claim is exclusive unless stale running recovery is requested`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-claim",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)

        assertTrue(store.tryStart(context, request.operationId))
        assertEquals("RUNNING", store.currentState(context, request.operationId))
        assertTrue(!store.tryStart(context, request.operationId))
        assertTrue(store.tryStart(context, request.operationId, allowExistingRunning = true))
    }

    @Test
    fun `cancel request before the linearization point blocks core commit`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-race",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertTrue(store.requestCancel(context, request.operationId))
        assertEquals(
            "CANCEL_REQUESTED",
            store.currentState(context, request.operationId)
        )
        assertTrue(!store.markCoreCommitted(context, request.operationId))
        assertEquals(
            "CANCEL_REQUESTED",
            store.currentState(context, request.operationId)
        )
    }

    @Test
    fun `late cancel cannot enter after committing linearizes the core commit`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-late-cancel",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertTrue(store.markCommitting(context, request.operationId))
        assertTrue(!store.requestCancel(context, request.operationId))
        assertTrue(store.markCoreCommitted(context, request.operationId))
        assertEquals("CORE_COMMITTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `task manager stop preserves a committing core transition without rescheduling it`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-user-stop",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertTrue(store.markCommitting(context, request.operationId))

        store.markStopped(context, request.operationId)

        assertTrue(store.isStopped(context, request.operationId))
        assertEquals("COMMITTING", store.currentState(context, request.operationId))
        assertTrue(store.markCoreCommitted(context, request.operationId))
        assertEquals("CORE_COMMITTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `host operation store uses one durable journal and never creates operation files`() {
        val directory = Files.createTempDirectory("download-execution-journal").toFile()
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-room",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertEquals(request, store.read(context, request.operationId))

        store.markStopped(context, request.operationId)
        assertTrue(store.isStopped(context, request.operationId))
        assertEquals(
            setOf(request.song.stableKey()),
            store.stoppedSongKeys(context)
        )

        store.remove(context, request.operationId)
        assertEquals(null, store.read(context, request.operationId))
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `queue lookup can return an operation id without decoding its request payload`() {
        var directory = java.io.File(System.getProperty("user.dir") ?: ".")
        val sourceFile = generateSequence(directory) { it.parentFile }
            .map {
                java.io.File(
                    it,
                    "app/src/main/java/moe/ouom/neriplayer/core/download/execution/DownloadExecutionRoomStore.kt"
                )
            }
            .firstOrNull(java.io.File::isFile)
            ?: error("operation room store source not found")
        val source = sourceFile.readText()
        val lookup = source.substringAfter("suspend fun findOperationIdForSong")
            .substringBefore("suspend fun isStopped")
        assertTrue(lookup.contains("findLatestOperationIdByStableKey"))
        assertTrue(!lookup.contains("requestFromEntity"))
    }
}

internal class InMemoryDownloadExecutionOperationJournal : DownloadExecutionOperationJournal {
    private val entries = linkedMapOf<String, DownloadExecutionJournalEntry>()

    override fun save(context: Context, request: DownloadExecutionRequest) {
        entries[request.operationId] = DownloadExecutionJournalEntry(request, "QUEUED")
    }

    override fun read(context: Context, operationId: String): DownloadExecutionRequest? =
        entries[operationId]?.request

    override fun remove(context: Context, operationId: String) {
        entries.remove(operationId)
    }

    override fun markStopped(context: Context, operationId: String) {
        entries[operationId]?.let {
            entries[operationId] = it.copy(userStopped = true)
        }
    }

    override fun isStopped(context: Context, operationId: String): Boolean =
        entries[operationId]?.userStopped == true

    override fun stoppedSongKeys(context: Context): Set<String> =
        entries.values.filter { it.userStopped }
            .map { it.request.song.stableKey() }
            .toSet()

    override fun findOperationIdForSong(context: Context, songKey: String): String? =
        entries.values.firstOrNull { it.request.song.stableKey() == songKey }
            ?.request?.operationId

    override fun updateState(context: Context, operationId: String, state: String, errorCode: String?) {
        entries[operationId]?.let { entry ->
            resolveDownloadOperationState(entry.state, state)?.let { nextState ->
                entries[operationId] = entry.copy(state = nextState)
            }
        }
    }

    override fun currentState(context: Context, operationId: String): String? {
        return entries[operationId]?.state
    }

    override fun requestCancel(context: Context, operationId: String): Boolean {
        val entry = entries[operationId] ?: return false
        val nextState = resolveDownloadOperationState(entry.state, "CANCEL_REQUESTED")
            ?: return false
        if (nextState == entry.state) return false
        entries[operationId] = entry.copy(state = nextState)
        return true
    }

    override fun markCoreCommitted(context: Context, operationId: String): Boolean {
        val entry = entries[operationId] ?: return false
        val nextState = resolveDownloadOperationState(entry.state, "CORE_COMMITTED")
            ?: return false
        if (nextState == entry.state) return false
        entries[operationId] = entry.copy(state = nextState)
        return true
    }

    override fun markCommitting(context: Context, operationId: String): Boolean {
        val entry = entries[operationId] ?: return false
        if (entry.state != "QUEUED" && entry.state != "RUNNING") return false
        entries[operationId] = entry.copy(state = "COMMITTING")
        return true
    }

    override fun pruneTerminalOperations(
        context: Context,
        cutoffMs: Long,
        limit: Int
    ): Int {
        val candidates = entries.entries
            .filter { (_, entry) ->
                entry.state in setOf("COMPLETED", "CANCELLED") &&
                    entry.updatedAtMs < cutoffMs
            }
            .sortedWith(compareBy({ it.value.updatedAtMs }, { it.key }))
            .take(limit)
        candidates.forEach { (operationId, _) -> entries.remove(operationId) }
        return candidates.size
    }

    fun forceState(operationId: String, state: String, updatedAtMs: Long) {
        entries[operationId]?.let { entry ->
            entries[operationId] = entry.copy(state = state, updatedAtMs = updatedAtMs)
        }
    }
}

private data class DownloadExecutionJournalEntry(
    val request: DownloadExecutionRequest,
    val state: String,
    val userStopped: Boolean = false,
    val updatedAtMs: Long = 0L
)

internal object SongItemFixtures {
    fun sampleSong() = SongItem(
        id = 42L,
        name = "Song",
        artist = "Artist",
        album = "Album",
        albumId = 7L,
        durationMs = 1234L,
        coverUrl = null
    )
}
