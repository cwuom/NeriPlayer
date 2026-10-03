package moe.ouom.neriplayer.data.sync.remote

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer
import moe.ouom.neriplayer.data.model.sync.SyncTrackStat
import moe.ouom.neriplayer.data.model.sync.SyncPlaybackStatBucket
import java.util.concurrent.CancellationException

class SyncRemoteSnapshotDecoder(
    private val sanitizer: (SyncData) -> SyncData,
    private val sanitizeTrack: (SyncTrackStat) -> SyncTrackStat?,
    private val sanitizeBucket: (SyncPlaybackStatBucket) -> SyncPlaybackStatBucket?,
    private val beforeSanitize: (SyncData) -> Unit = {}
) {
    constructor(sanitizer: (SyncData) -> SyncData) : this(
        sanitizer,
        { sanitizer(SyncData(playbackStats = listOf(it))).playbackStats.singleOrNull() },
        { sanitizer(SyncData(playbackStatBuckets = listOf(it))).playbackStatBuckets.singleOrNull() }
    )
    fun sanitize(data: SyncData): SyncData {
        beforeSanitize(data)
        return sanitizer(data)
    }
    fun sanitize(data: SyncTrackStat): SyncTrackStat? = sanitizeTrack(data)
    fun sanitize(data: SyncPlaybackStatBucket): SyncPlaybackStatBucket? = sanitizeBucket(data)
    fun decode(content: ByteArray, emptyContentError: () -> Exception): Result<SyncData> {
        if (content.isEmpty()) return Result.failure(emptyContentError())
        return try {
            SyncDataSerializer.ensureRemoteContentSize(content)
            Result.success(sanitize(SyncDataSerializer.deserialize(content)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun decodeForMigration(
        content: ByteArray,
        emptyContentError: () -> Exception,
        authorizeMigration: suspend (ByteArray) -> Unit,
        beforeNormalization: (SyncData) -> Unit = {},
        checkActive: () -> Unit = {}
    ): Result<SyncData> {
        if (content.isEmpty()) return Result.failure(emptyContentError())
        return try {
            checkActive()
            SyncDataSerializer.ensureRemoteContentSize(content)
            val data = SyncDataSerializer.deserialize(content)
            authorizeMigration(content)
            checkActive()
            beforeNormalization(data)
            Result.success(sanitize(data))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
