package moe.ouom.neriplayer.core.download.execution.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import moe.ouom.neriplayer.data.identity.stableKey
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadOperationEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadExecutionJournalSchemaVersionTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: NeriUserDataDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
        DownloadExecutionRoomStore.networkPolicyByOperationId.clear()
    }

    @Test
    fun `queued work written without a schema version or at the current version is resumed`() = runTest {
        insertOperation("op-missing", song(1L)) { remove("schemaVersion") }
        insertOperation("op-zero", song(2L)) { put("schemaVersion", 0) }
        insertOperation("op-current", song(3L)) { put("schemaVersion", 1) }

        val entries = DownloadExecutionRoomReadStore.listByStatesAnyLibrary(
            context,
            listOf(QUEUED),
            database = database
        )

        assertEquals(
            listOf("op-current", "op-missing", "op-zero"),
            entries.map { it.request.operationId }.sorted()
        )
        listOf("op-missing", "op-zero", "op-current").forEach { operationId ->
            assertEquals(operationId, QUEUED, stateOf(operationId))
        }
    }

    @Test
    fun `queued work from a newer schema is skipped but kept for a later upgrade`() = runTest {
        insertOperation("op-newer", song(4L)) { put("schemaVersion", 2) }
        insertOperation("op-current", song(5L)) { put("schemaVersion", 1) }

        val entries = DownloadExecutionRoomReadStore.listByStatesAnyLibrary(
            context,
            listOf(QUEUED),
            database = database
        )
        val metadata = DownloadExecutionRoomReadStore.readOperationRequestMetadata(
            context,
            listOf("op-newer", "op-current"),
            database = database
        )

        assertEquals(listOf("op-current"), entries.map { it.request.operationId })
        assertEquals(setOf("op-current"), metadata.keys)
        assertEquals(QUEUED, stateOf("op-newer"))
        assertEquals(null, lastErrorOf("op-newer"))
    }

    @Test
    fun `request metadata accepts payloads written without a schema version`() = runTest {
        val song = song(6L)
        insertOperation("op-missing", song) { remove("schemaVersion") }

        val metadata = DownloadExecutionRoomReadStore.readOperationRequestMetadata(
            context,
            listOf("op-missing"),
            database = database
        )

        assertEquals(song.stableKey(), metadata.getValue("op-missing").stableKey)
        assertEquals("lease-op-missing", metadata.getValue("op-missing").artifactLeaseId)
        assertEquals(QUEUED, stateOf("op-missing"))
    }

    @Test
    fun `payloads with an impossible schema version are still invalidated`() = runTest {
        insertOperation("op-negative", song(7L)) { put("schemaVersion", -1) }

        val entries = DownloadExecutionRoomReadStore.listByStatesAnyLibrary(
            context,
            listOf(QUEUED),
            database = database
        )

        assertEquals(emptyList<String>(), entries.map { it.request.operationId })
        assertEquals("INVALID", stateOf("op-negative"))
        assertEquals("INVALID_OPERATION_PAYLOAD", lastErrorOf("op-negative"))
    }

    private suspend fun insertOperation(
        operationId: String,
        song: SongItem,
        editPayload: JSONObject.() -> Unit
    ) {
        val payload = DownloadExecutionRoomStore.requestToJson(
            DownloadExecutionRequest(
                operationId = operationId,
                song = song,
                artifactLeaseId = "lease-$operationId"
            )
        ).apply(editPayload)
        database.downloadOperationDao().upsert(
            DownloadOperationEntity(
                operationId = operationId,
                stableKey = song.stableKey(),
                libraryId = "library-a",
                state = QUEUED,
                queueOrder = 0,
                sourceHintJson = payload.toString(),
                stagingDirName = operationId,
                bytesWritten = 0L,
                totalBytes = null,
                resumeJson = null,
                retryCount = 0,
                nextRetryAtMs = null,
                lastErrorCode = null,
                createdAtMs = 10L,
                updatedAtMs = 20L
            )
        )
    }

    private suspend fun stateOf(operationId: String): String? {
        return database.downloadOperationDao().findHeader(operationId)?.state
    }

    private suspend fun lastErrorOf(operationId: String): String? {
        return database.downloadOperationDao().findHeader(operationId)?.lastErrorCode
    }

    private fun song(id: Long): SongItem {
        return SongItem(
            id = id,
            name = "Song $id",
            artist = "Artist",
            album = "Album",
            albumId = 7L,
            durationMs = 180_000L,
            coverUrl = null
        )
    }

    private companion object {
        const val QUEUED = "QUEUED"
    }
}
