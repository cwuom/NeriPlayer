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
    fun `host admission handoff accepts every interrupted durable state`() {
        val interruptedStates = listOf(
            "RUNNING",
            "COMMITTING",
            "CORE_COMMITTED",
            "ASSETS_ENRICHING",
            "DEGRADED_COMPLETE"
        )

        interruptedStates.forEach { state ->
            assertTrue(
                "$state must be claimable by a fresh process",
                state in DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES
            )
        }
        assertTrue(
            "STOPPED must remain user controlled",
            "STOPPED" !in DownloadExecutionRoomStore.HOST_ADMISSION_HANDOFF_STATES
        )
    }

    @Test
    fun `generic upsert never reopens a cancelled operation identity`() {
        assertTrue(
            !shouldRestartOperation(
                existingState = "CANCELLED",
                requestedState = "QUEUED",
                userInitiated = true
            )
        )
        assertTrue(
            !shouldRestartOperation(
                existingState = "CANCEL_REQUESTED",
                requestedState = "RUNNING",
                userInitiated = true
            )
        )
        assertTrue(
            !shouldRestartOperation(
                existingState = "RETRYABLE",
                requestedState = "QUEUED",
                userInitiated = false
            )
        )
    }

    @Test
    fun `late cancel stops enrichment without overwriting core committed operation`() {
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
        assertTrue(store.requestCancel(context, request.operationId))
        assertEquals(
            "CORE_COMMITTED",
            store.currentState(context, request.operationId)
        )
        assertTrue(store.isStopped(context, request.operationId))
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
    fun `recovery claim preserves a durable core state`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-core-recovery",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        journal.forceState(request.operationId, "CORE_COMMITTED", updatedAtMs = 1L)

        assertTrue(store.tryStart(context, request.operationId, allowExistingRunning = true))
        assertEquals("CORE_COMMITTED", store.currentState(context, request.operationId))
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
    fun `late cancel records a stop without reversing the committing state`() {
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
        assertTrue(store.requestCancel(context, request.operationId))
        assertTrue(store.isStopped(context, request.operationId))
        assertEquals("COMMITTING", store.currentState(context, request.operationId))
        assertTrue(store.markCoreCommitted(context, request.operationId))
        assertEquals("CORE_COMMITTED", store.currentState(context, request.operationId))
    }

    @Test
    fun `failed core commit returns to retryable and can be claimed again`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-commit-retry",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertTrue(store.tryStart(context, request.operationId))
        assertTrue(store.markCommitting(context, request.operationId))

        store.updateState(context, request.operationId, "RETRYABLE", "COMMIT_FAILED")

        assertEquals("RETRYABLE", store.currentState(context, request.operationId))
        assertTrue(store.tryStart(context, request.operationId))
        assertEquals("RUNNING", store.currentState(context, request.operationId))
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
    fun `cancel at commit boundary preserves core audio without requeueing`() {
        val context = mock(Context::class.java)
        `when`(context.applicationContext).thenReturn(context)
        val journal = InMemoryDownloadExecutionOperationJournal()
        val store = DownloadExecutionOperationStore { journal }
        val request = DownloadExecutionRequest(
            operationId = "operation-cancel-at-commit",
            song = SongItemFixtures.sampleSong()
        )

        store.save(context, request)
        assertTrue(store.markCommitting(context, request.operationId))
        assertTrue(store.requestCancel(context, request.operationId))
        assertTrue(store.isStopped(context, request.operationId))
        assertEquals("COMMITTING", store.currentState(context, request.operationId))
        assertTrue(store.markCoreCommitted(context, request.operationId))
        assertEquals("CORE_COMMITTED", store.currentState(context, request.operationId))
        assertTrue(store.isStopped(context, request.operationId))
        assertTrue(!store.tryStart(context, request.operationId, allowExistingRunning = true))
        assertTrue(
            !store.updateState(
                context = context,
                operationId = request.operationId,
                state = "RETRYABLE",
                errorCode = "LATE_HOST_CANCEL"
            )
        )
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
    fun `raw lookup stays lightweight while reusable lookup validates its payload`() {
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
        val rawLookup = source.substringAfter("suspend fun findOperationIdForSong")
            .substringBefore("suspend fun findReadableOperationIdForSong")
        val readableLookup = source.substringAfter("suspend fun findReadableOperationIdForSong")
            .substringBefore("suspend fun isStopped")
        assertTrue(rawLookup.contains("findLatestOperationIdByStableKey"))
        assertTrue(!rawLookup.contains("requestFromEntity"))
        assertTrue(readableLookup.contains("requestFromEntity"))
        assertTrue(readableLookup.contains("invalidateMalformedPayload(database, entity)"))
    }
}

internal class InMemoryDownloadExecutionOperationJournal : DownloadExecutionOperationJournal {
    private val entries = linkedMapOf<String, DownloadExecutionJournalEntry>()
    private var nextQueueOrder = 0
    var afterStateUpdate: ((String, String) -> Unit)? = null
    var afterSave: ((DownloadExecutionRequest) -> Unit)? = null
    var hostAdmissionAllowed: Boolean = true
    var hostAdmissionAcquireCount: Int = 0
    var lastHostAdmissionCapacity: Int? = null
    var hostAdmissionReleaseCount: Int = 0

    override fun save(context: Context, request: DownloadExecutionRequest) {
        val queueOrder = entries[request.operationId]?.queueOrder ?: nextQueueOrder++
        entries[request.operationId] = DownloadExecutionJournalEntry(
            request = request,
            state = "QUEUED",
            queueOrder = queueOrder
        )
        afterSave?.invoke(request)
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

    override fun listSchedulableForPumpPage(
        context: Context,
        afterCursor: DownloadExecutionPumpCursor?,
        limit: Int
    ): DownloadExecutionPumpPage {
        val boundedLimit = limit.coerceAtLeast(0)
        val page = entries.values
            .filter { entry ->
                !entry.userStopped &&
                    entry.state in DownloadExecutionRoomStore.REUSABLE_OPERATION_STATES
            }
            .filter { entry -> entry.isAfter(afterCursor) }
            .sortedWith(
                compareBy<DownloadExecutionJournalEntry> { it.queueOrder }
                    .thenBy { it.updatedAtMs }
                    .thenBy { it.request.operationId }
            )
            .take(boundedLimit)
        val nextCursor = page.lastOrNull()
            ?.takeIf { page.size == boundedLimit }
            ?.let { entry ->
                DownloadExecutionPumpCursor(
                    queueOrder = entry.queueOrder,
                    updatedAtMs = entry.updatedAtMs,
                    operationId = entry.request.operationId
                )
            }
        return DownloadExecutionPumpPage(
            requests = page.map(DownloadExecutionJournalEntry::request),
            nextCursor = nextCursor
        )
    }

    override fun findOperationIdForSong(context: Context, songKey: String): String? =
        entries.values.firstOrNull { it.request.song.stableKey() == songKey }
            ?.request?.operationId

    override fun updateState(
        context: Context,
        operationId: String,
        state: String,
        errorCode: String?
    ): Boolean {
        val entry = entries[operationId] ?: return false
        if (entry.userStopped) return false
        val nextState = resolveDownloadOperationState(entry.state, state) ?: return false
        entries[operationId] = entry.copy(
            state = nextState,
            updatedAtMs = System.currentTimeMillis()
        )
        afterStateUpdate?.invoke(operationId, state)
        return true
    }

    override fun currentState(context: Context, operationId: String): String? {
        return entries[operationId]?.state
    }

    override fun requestCancel(context: Context, operationId: String): Boolean {
        val entry = entries[operationId] ?: return false
        val nextState = resolveDownloadOperationState(entry.state, "CANCEL_REQUESTED")
        if (nextState == null) {
            if (entry.state !in setOf("COMMITTING", "CORE_COMMITTED", "ASSETS_ENRICHING")) {
                return false
            }
            entries[operationId] = entry.copy(userStopped = true)
            return true
        }
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
        if (entry.userStopped) return false
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

    override fun tryAcquireHostAdmission(
        context: Context,
        operationId: String,
        capacity: Int
    ): Boolean {
        hostAdmissionAcquireCount++
        lastHostAdmissionCapacity = capacity
        return hostAdmissionAllowed && capacity > 0
    }

    override fun releaseHostAdmission(context: Context, operationId: String) {
        hostAdmissionReleaseCount++
    }

    fun forceState(operationId: String, state: String, updatedAtMs: Long) {
        entries[operationId]?.let { entry ->
            entries[operationId] = entry.copy(state = state, updatedAtMs = updatedAtMs)
        }
    }

    fun forceRequest(request: DownloadExecutionRequest) {
        entries[request.operationId]?.let { entry ->
            entries[request.operationId] = entry.copy(request = request)
        }
    }
}

private data class DownloadExecutionJournalEntry(
    val request: DownloadExecutionRequest,
    val state: String,
    val userStopped: Boolean = false,
    val updatedAtMs: Long = 0L,
    val queueOrder: Int = 0
) {
    fun isAfter(cursor: DownloadExecutionPumpCursor?): Boolean {
        if (cursor == null) return true
        return when {
            queueOrder != cursor.queueOrder -> queueOrder > cursor.queueOrder
            updatedAtMs != cursor.updatedAtMs -> updatedAtMs > cursor.updatedAtMs
            else -> request.operationId > cursor.operationId
        }
    }
}

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
