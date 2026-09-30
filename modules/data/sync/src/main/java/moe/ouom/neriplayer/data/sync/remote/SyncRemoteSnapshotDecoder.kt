package moe.ouom.neriplayer.data.sync.remote

import moe.ouom.neriplayer.data.model.sync.SyncData
import moe.ouom.neriplayer.data.sync.codec.SyncDataSerializer

class SyncRemoteSnapshotDecoder(private val sanitize: (SyncData) -> SyncData) {
    fun decode(content: ByteArray, emptyContentError: () -> Exception): Result<SyncData> {
        if (content.isEmpty()) return Result.failure(emptyContentError())
        return try {
            SyncDataSerializer.ensureRemoteContentSize(content)
            Result.success(sanitize(SyncDataSerializer.deserialize(content)))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
}
