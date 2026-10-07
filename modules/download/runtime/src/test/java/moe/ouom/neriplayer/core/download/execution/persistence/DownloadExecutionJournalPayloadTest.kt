package moe.ouom.neriplayer.core.download.execution.persistence

import android.content.Context
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.dao.DownloadBatchDao
import moe.ouom.neriplayer.data.local.database.dao.DownloadOperationDao
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchState
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationHeaderRow
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import moe.ouom.neriplayer.data.model.settings.download.DownloadAudioQualitySelection
import moe.ouom.neriplayer.data.settings.download.normalized
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions

class DownloadExecutionJournalPayloadTest {
    @After
    fun tearDown() {
        DownloadExecutionRoomStore.networkPolicyByOperationId.clear()
    }

    @Test
    fun `convergence priority ranks post core work ahead of transfer work`() {
        val states = listOf(
            "DEGRADED_COMPLETE",
            "ASSETS_ENRICHING",
            "CORE_COMMITTED",
            "COMMITTING",
            "RUNNING",
            "RETRYABLE",
            "QUEUED",
            "PENDING_QUEUE"
        )

        assertEquals((8 downTo 1).toList(), states.map(::executionConvergencePriority))
        assertEquals(0, executionConvergencePriority("COMPLETED"))
        assertEquals(0, executionConvergencePriority(WAITING_STORAGE_MUTATION_OPERATION_STATE))
    }

    @Test
    fun `payload timestamps never move backwards behind the previous write`() {
        assertEquals(500L, DownloadExecutionRoomStore.nextPayloadUpdatedAt(null, 500L))
        assertEquals(1_001L, DownloadExecutionRoomStore.nextPayloadUpdatedAt(1_000L, 500L))
        assertEquals(2_000L, DownloadExecutionRoomStore.nextPayloadUpdatedAt(1_000L, 2_000L))
        assertEquals(Long.MAX_VALUE, DownloadExecutionRoomStore.nextPayloadUpdatedAt(Long.MAX_VALUE, 5L))
    }

    @Test
    fun `commit boundary cancellation applies only to committing work the user has not stopped`() {
        DownloadExecutionRoomStore.COMMIT_BOUNDARY_CANCEL_STATES.forEach { state ->
            assertTrue(state, DownloadExecutionRoomStore.requiresCommitBoundaryCancellation(state, false))
            assertFalse(state, DownloadExecutionRoomStore.requiresCommitBoundaryCancellation(state, true))
        }
        assertFalse(DownloadExecutionRoomStore.requiresCommitBoundaryCancellation("RUNNING", false))
    }

    @Test
    fun `request journal json writes optional routing fields only when present`() {
        val song = song()
        val minimal = DownloadExecutionRequest(
            operationId = "op-minimal",
            song = song,
            requiresWifiNetwork = false,
            artifactLeaseId = "lease-minimal",
            userInitiated = false
        )

        val minimalJson = DownloadExecutionRoomStore.requestToJson(minimal)

        assertEquals(DownloadExecutionRoomStore.JOURNAL_PAYLOAD_VERSION, minimalJson.getInt("schemaVersion"))
        assertEquals(song.stableKey(), minimalJson.getString("sourceStableKey"))
        assertEquals("lease-minimal", minimalJson.getString("artifactLeaseId"))
        assertFalse(minimalJson.getBoolean("requiresWifiNetwork"))
        assertFalse(minimalJson.getBoolean("userInitiated"))
        assertFalse(minimalJson.getBoolean("preserveStaging"))
        assertEquals(song.name, minimalJson.getJSONObject("song").getString("name"))
        listOf("attemptId", "batchId", "batchGeneration", "downloadAudioQuality").forEach { key ->
            assertFalse(key, minimalJson.has(key))
        }

        val routedJson = DownloadExecutionRoomStore.requestToJson(routedRequest(song))

        assertEquals(4L, routedJson.getLong("attemptId"))
        assertEquals("batch-1", routedJson.getString("batchId"))
        assertEquals(2L, routedJson.getLong("batchGeneration"))
        assertTrue(routedJson.getBoolean("preserveStaging"))
        assertTrue(routedJson.getBoolean("requiresFreshTransfer"))
        val quality = routedJson.getJSONObject("downloadAudioQuality")
        assertEquals("Lossless", quality.getString("neteaseQuality"))
        assertEquals("High", quality.getString("youtubeQuality"))
        assertEquals("192K", quality.getString("biliQuality"))
    }

    @Test
    fun `chunked source hint reader reassembles payloads larger than one chunk`() = runTest {
        val payload = buildString {
            append("{\"lyric\":\"")
            repeat(70_000) { append('歌') }
            repeat(5) { append("\uD83C\uDFB5") }
            append("\"}")
        }
        val header = header(operationId = "op-lyrics", updatedAtMs = 77L)
        val dao = mock(DownloadOperationDao::class.java)
        val requestedOffsets = stubSourceHint(dao, header, payload)

        assertEquals(payload, DownloadExecutionRoomStore.readSourceHintJson(dao, header))
        assertEquals(listOf(1, 65_537), requestedOffsets)
    }

    @Test
    fun `source hint reader rejects inconsistent payload reads`() = runTest {
        assertNull(readSourceHint(length = null))
        assertNull(readSourceHint(length = -1))
        assertEquals("", readSourceHint(length = 0))
        assertNull(readSourceHint(length = 8, "abcd", null))
        assertNull(readSourceHint(length = 8, "abcd", ""))
        assertNull(readSourceHint(length = 3, "abcd"))
        assertEquals("abcdefgh", readSourceHint(length = 8, "abcd", "efgh"))
    }

    @Test
    fun `journal payload round trips through the chunked header reader`() = runTest {
        val song = song()
        val request = routedRequest(song)
        val header = header(operationId = request.operationId, stableKey = song.stableKey())
        val dao = mock(DownloadOperationDao::class.java)
        stubSourceHint(dao, header, DownloadExecutionRoomStore.requestToJson(request).toString())

        val read = DownloadExecutionRoomStore.readRequestFromHeader(dao, header)

        assertTrue(read.payloadWasRead)
        val decoded = checkNotNull(read.request)
        assertEquals(request.operationId, decoded.operationId)
        assertEquals(song.stableKey(), decoded.song.stableKey())
        assertEquals(request.artifactLeaseId, decoded.artifactLeaseId)
        assertEquals(request.attemptId, decoded.attemptId)
        assertEquals(request.batchId, decoded.batchId)
        assertEquals(request.batchGeneration, decoded.batchGeneration)
        assertEquals(request.preserveStaging, decoded.preserveStaging)
        assertEquals(request.requiresFreshTransfer, decoded.requiresFreshTransfer)
        assertEquals(request.requiresWifiNetwork, decoded.requiresWifiNetwork)
        assertEquals(request.userInitiated, decoded.userInitiated)
        assertEquals(
            DownloadAudioQualitySelection.normalized("Lossless", "High", "192K"),
            decoded.downloadAudioQuality
        )
        assertEquals(
            false,
            DownloadExecutionRoomStore.networkPolicyByOperationId[request.operationId]?.requiresWifiNetwork
        )
    }

    @Test
    fun `waiting storage mutation lookup skips the target user stops and unreadable payloads`() = runTest {
        val song = song()
        val stableKey = song.stableKey()
        val dao = mock(DownloadOperationDao::class.java)
        val database = database(operationDao = dao)
        val target = header(operationId = "op-target", stableKey = stableKey)
        val stopped = header(operationId = "op-stopped", stableKey = stableKey, stopRequestedByUser = true)
        val unreadable = header(operationId = "op-unreadable", stableKey = stableKey)
        val valid = header(operationId = "op-valid", stableKey = stableKey)
        doReturn(listOf(target, stopped, unreadable, valid)).`when`(dao).findAllHeadersByStableKey(
            "library-a",
            stableKey,
            listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
        )
        doReturn(null).`when`(dao).findSourceHintJsonLength("op-unreadable", unreadable.updatedAtMs)
        stubSourceHint(
            dao,
            valid,
            DownloadExecutionRoomStore.requestToJson(
                DownloadExecutionRequest(operationId = "op-valid", song = song, artifactLeaseId = "lease")
            ).toString()
        )

        assertTrue(
            DownloadExecutionRoomStore.hasOtherValidWaitingStorageMutation(
                database = database,
                targetOperationId = "op-target",
                targetLibraryId = "library-a",
                targetStableKey = stableKey
            )
        )
        verify(dao, never()).findSourceHintJsonLength("op-target", target.updatedAtMs)
        verify(dao, never()).findSourceHintJsonLength("op-stopped", stopped.updatedAtMs)
        verify(dao, never()).invalidateMalformedPayloadAtVersion(anyString(), anyString(), anyLong(), anyLong())
    }

    @Test
    fun `malformed waiting payloads are invalidated and never count as another mutation`() = runTest {
        val song = song()
        val stableKey = song.stableKey()
        val otherSong = song.copy(id = 99L, name = "Other")
        val dao = mock(DownloadOperationDao::class.java)
        val database = database(operationDao = dao)
        val brokenJson = header(operationId = "op-broken", stableKey = stableKey, updatedAtMs = 900L)
        val foreignSong = header(operationId = "op-foreign", stableKey = stableKey, updatedAtMs = 901L)
        doReturn(listOf(brokenJson, foreignSong)).`when`(dao).findAllHeadersByStableKey(
            "library-a",
            stableKey,
            listOf(WAITING_STORAGE_MUTATION_OPERATION_STATE)
        )
        stubSourceHint(dao, brokenJson, "{not json")
        stubSourceHint(
            dao,
            foreignSong,
            DownloadExecutionRoomStore.requestToJson(
                DownloadExecutionRequest(operationId = "op-foreign", song = otherSong, artifactLeaseId = "lease")
            ).toString()
        )
        doReturn(1).`when`(dao).invalidateMalformedPayloadAtVersion(
            sameValue("op-broken"),
            sameValue(WAITING_STORAGE_MUTATION_OPERATION_STATE),
            eq(900L),
            anyLong()
        )
        doReturn(0).`when`(dao).invalidateMalformedPayloadAtVersion(
            sameValue("op-foreign"),
            sameValue(WAITING_STORAGE_MUTATION_OPERATION_STATE),
            eq(901L),
            anyLong()
        )

        assertFalse(
            DownloadExecutionRoomStore.hasOtherValidWaitingStorageMutation(
                database = database,
                targetOperationId = "op-target",
                targetLibraryId = "library-a",
                targetStableKey = stableKey
            )
        )
        verify(dao).deleteHostAdmission("op-broken")
        verify(dao, never()).deleteHostAdmission("op-foreign")
    }

    @Test
    fun `state transition without expected states is rejected before opening the database`() = runTest {
        val context = mock(Context::class.java)

        assertFalse(
            DownloadExecutionRoomStore.transitionStateAtomically(
                context = context,
                operationId = "op-1",
                expectedStates = emptyList(),
                requestedState = "RUNNING",
                errorCode = null
            )
        )
        verifyNoInteractions(context)
    }

    @Test
    fun `batch recovery snapshot is consistent only for a complete ordered member set`() {
        val batch = batch(totalCount = 3)
        val complete = listOf(member(0), member(1), member(2))

        assertTrue(DownloadExecutionRoomStore.DownloadBatchRecoverySnapshot(batch, complete).isConsistent)
        mapOf(
            "missing member" to complete.take(2),
            "out of order ordinals" to listOf(member(1), member(0), member(2)),
            "duplicate stable key" to listOf(member(0), member(1), member(2, stableKey = "song-1")),
            "foreign batch member" to listOf(member(0), member(1), member(2, batchId = "batch-2"))
        ).forEach { (scenario, members) ->
            assertFalse(
                scenario,
                DownloadExecutionRoomStore.DownloadBatchRecoverySnapshot(batch, members).isConsistent
            )
        }
    }

    @Test
    fun `batch snapshot insert writes the batch before its members`() = runTest {
        val batchDao = mock(DownloadBatchDao::class.java)
        val database = database(batchDao = batchDao)
        val batch = batch(totalCount = 2)
        val members = listOf(member(0), member(1))

        DownloadExecutionRoomStore.insertBatchSnapshotInTransaction(database, batch, members)

        val order = inOrder(batchDao)
        order.verify(batchDao).insertBatch(batch)
        order.verify(batchDao).insertMembers(members)
    }

    @Test
    fun `batch snapshot insert rejects inconsistent member sets before writing`() = runTest {
        val batch = batch(totalCount = 3)
        val cases = linkedMapOf(
            "batch member count does not match total count" to listOf(member(0), member(1)),
            "batch members must have unique stable keys" to
                listOf(member(0), member(1), member(2, stableKey = "song-0")),
            "batch members must have unique ordinals" to
                listOf(member(0), member(1, stableKey = "a"), member(1, stableKey = "b")),
            "batch member ordinals must be contiguous" to listOf(member(0), member(1), member(3)),
            "batch member belongs to a different batch" to
                listOf(member(0), member(1), member(2, batchId = "batch-2"))
        )

        cases.forEach { (expectedMessage, members) ->
            val batchDao = mock(DownloadBatchDao::class.java)
            val database = database(batchDao = batchDao)

            val error = runCatching {
                DownloadExecutionRoomStore.insertBatchSnapshotInTransaction(database, batch, members)
            }.exceptionOrNull()

            assertTrue(expectedMessage, error is IllegalArgumentException)
            assertEquals(expectedMessage, error?.message)
            verifyNoInteractions(batchDao)
        }
    }

    private suspend fun readSourceHint(length: Int?, vararg chunks: String?): String? {
        val dao = mock(DownloadOperationDao::class.java)
        val header = header()
        doReturn(length).`when`(dao).findSourceHintJsonLength(header.operationId, header.updatedAtMs)
        val remaining = ArrayDeque(chunks.toList())
        doAnswer { remaining.removeFirst() }.`when`(dao)
            .findSourceHintJsonChunk(anyString(), anyInt(), anyInt(), anyLong())
        return DownloadExecutionRoomStore.readSourceHintJson(dao, header)
    }

    /** Emulates SQLite `length()`/`substr()`, which count code points rather than UTF-16 units. */
    private suspend fun stubSourceHint(
        dao: DownloadOperationDao,
        header: DownloadOperationHeaderRow,
        payload: String
    ): List<Int> {
        val requestedOffsets = mutableListOf<Int>()
        val codePoints = payload.codePointCount(0, payload.length)
        doReturn(codePoints).`when`(dao).findSourceHintJsonLength(header.operationId, header.updatedAtMs)
        doAnswer { invocation ->
            val startOffset = invocation.getArgument<Int>(1)
            val chunkLength = invocation.getArgument<Int>(2)
            requestedOffsets += startOffset
            val startIndex = (startOffset - 1).coerceIn(0, codePoints)
            val endIndex = (startIndex + chunkLength).coerceAtMost(codePoints)
            payload.substring(
                payload.offsetByCodePoints(0, startIndex),
                payload.offsetByCodePoints(0, endIndex)
            )
        }.`when`(dao).findSourceHintJsonChunk(
            sameValue(header.operationId),
            anyInt(),
            anyInt(),
            eq(header.updatedAtMs)
        )
        return requestedOffsets
    }

    /** Mockito's object `eq` returns null, which Kotlin rejects for non-null parameters. */
    private fun <T : Any> sameValue(value: T): T {
        eq(value)
        return value
    }

    private fun database(
        operationDao: DownloadOperationDao = mock(DownloadOperationDao::class.java),
        batchDao: DownloadBatchDao = mock(DownloadBatchDao::class.java)
    ): NeriUserDataDatabase {
        val database = mock(NeriUserDataDatabase::class.java)
        doReturn(operationDao).`when`(database).downloadOperationDao()
        doReturn(batchDao).`when`(database).downloadBatchDao()
        return database
    }

    private fun routedRequest(song: SongItem): DownloadExecutionRequest {
        return DownloadExecutionRequest(
            operationId = "op-routed",
            song = song,
            preserveStaging = true,
            requiresWifiNetwork = false,
            attemptId = 4L,
            artifactLeaseId = "lease-routed",
            userInitiated = true,
            downloadAudioQuality = DownloadAudioQualitySelection(
                neteaseQuality = "Lossless",
                youtubeQuality = "High",
                biliQuality = "192K"
            ),
            batchId = "batch-1",
            batchGeneration = 2L,
            requiresFreshTransfer = true
        )
    }

    private fun song(): SongItem {
        return SongItem(
            id = 42L,
            name = "Song",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 180_000L,
            coverUrl = null
        )
    }

    private fun header(
        operationId: String = "op-1",
        stableKey: String = "stable-1",
        updatedAtMs: Long = 100L,
        stopRequestedByUser: Boolean = false
    ): DownloadOperationHeaderRow {
        return DownloadOperationHeaderRow(
            operationId = operationId,
            stableKey = stableKey,
            libraryId = "library-a",
            state = WAITING_STORAGE_MUTATION_OPERATION_STATE,
            queueOrder = 0,
            stagingDirName = operationId,
            bytesWritten = 0L,
            totalBytes = null,
            retryCount = 0,
            nextRetryAtMs = null,
            lastErrorCode = null,
            stopRequestedByUser = stopRequestedByUser,
            createdAtMs = 10L,
            updatedAtMs = updatedAtMs,
            hostProcessToken = null,
            hostAdmittedAtMs = null,
            batchId = null,
            batchGeneration = null
        )
    }

    private fun batch(totalCount: Int, batchId: String = "batch-1"): DownloadBatchEntity {
        return DownloadBatchEntity(
            batchId = batchId,
            generation = 1L,
            totalCount = totalCount,
            stateBits = DownloadBatchState.OPEN,
            clearEpoch = 0L,
            networkGeneration = null,
            updatedAtMs = 10L,
            createdAtMs = 5L
        )
    }

    private fun member(
        ordinal: Int,
        stableKey: String = "song-$ordinal",
        batchId: String = "batch-1"
    ): DownloadBatchMemberEntity {
        return DownloadBatchMemberEntity(
            batchId = batchId,
            ordinal = ordinal,
            stableKey = stableKey,
            updatedAtMs = 10L
        )
    }
}
