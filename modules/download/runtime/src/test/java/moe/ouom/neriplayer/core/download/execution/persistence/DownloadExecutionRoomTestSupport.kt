package moe.ouom.neriplayer.core.download.execution.persistence

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import moe.ouom.neriplayer.core.download.execution.persistence.DownloadExecutionRoomStore.DownloadBatchIdentity
import moe.ouom.neriplayer.data.local.database.NeriUserDataDatabase
import moe.ouom.neriplayer.data.local.database.entity.DownloadBatchMemberEntity
import moe.ouom.neriplayer.data.model.SongItem
import moe.ouom.neriplayer.data.model.download.DownloadExecutionRequest
import moe.ouom.neriplayer.data.identity.stableKey

/** In-memory Room fixture for driving the download execution journal through its public entry points. */
internal class DownloadExecutionRoomFixture {
    val context: Context = ApplicationProvider.getApplicationContext()
    val database: NeriUserDataDatabase = Room
        .inMemoryDatabaseBuilder(context, NeriUserDataDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    val operationDao get() = database.downloadOperationDao()
    val batchDao get() = database.downloadBatchDao()

    fun close() {
        database.close()
        DownloadExecutionRoomStore.networkPolicyByOperationId.clear()
    }

    suspend fun upsert(
        request: DownloadExecutionRequest,
        state: String,
        queueOrder: Int = 0,
        createdAtMs: Long? = null
    ) {
        DownloadExecutionRoomStore.upsert(
            context = context,
            request = request,
            state = state,
            queueOrder = queueOrder,
            createdAtMs = createdAtMs,
            database = database
        )
    }

    suspend fun createBatch(
        songs: List<SongItem>,
        initiallyCompletedSongKeys: Set<String> = emptySet(),
        networkGeneration: Long? = null,
        nowMs: Long = 1_000L
    ): DownloadBatchIdentity {
        return DownloadExecutionRoomStore.createBatchSnapshot(
            context = context,
            songs = songs,
            initiallyCompletedSongKeys = initiallyCompletedSongKeys,
            networkGeneration = networkGeneration,
            nowMs = nowMs,
            database = database
        )
    }

    /** Persists one queued request per song inside [identity] and binds it to its batch member. */
    suspend fun enqueueBatchMembers(
        identity: DownloadBatchIdentity,
        songs: List<SongItem>,
        state: String = "QUEUED",
        attemptId: Long? = null,
        requiresWifiNetwork: Boolean = true
    ): List<DownloadExecutionRequest> {
        val requests = songs.map { song ->
            request(
                song = song,
                attemptId = attemptId,
                requiresWifiNetwork = requiresWifiNetwork,
                batch = identity
            )
        }
        requests.forEach { request -> upsert(request, state) }
        DownloadExecutionRoomStore.attachBatchIdentity(
            context = context,
            identity = identity,
            requests = requests,
            database = database
        )
        return requests
    }

    suspend fun member(identity: DownloadBatchIdentity, song: SongItem): DownloadBatchMemberEntity {
        return requireNotNull(batchDao.findMember(identity.batchId, song.stableKey()))
    }

    suspend fun batchStateBits(identity: DownloadBatchIdentity): Int {
        return requireNotNull(batchDao.findBatch(identity.batchId, identity.generation)).stateBits
    }

    suspend fun state(operationId: String): String? = operationDao.findState(operationId)
}

internal fun testSong(id: Long, name: String = "Song $id"): SongItem {
    return SongItem(
        id = id,
        name = name,
        artist = "Artist",
        album = "Album",
        albumId = 7L,
        durationMs = 180_000L,
        coverUrl = null
    )
}

internal fun request(
    song: SongItem,
    operationId: String = "op-${song.id}",
    attemptId: Long? = null,
    requiresWifiNetwork: Boolean = true,
    userInitiated: Boolean = true,
    artifactLeaseId: String = "lease-${song.id}",
    batch: DownloadBatchIdentity? = null
): DownloadExecutionRequest {
    return DownloadExecutionRequest(
        operationId = operationId,
        song = song,
        requiresWifiNetwork = requiresWifiNetwork,
        attemptId = attemptId,
        artifactLeaseId = artifactLeaseId,
        userInitiated = userInitiated,
        batchId = batch?.batchId,
        batchGeneration = batch?.generation
    )
}
