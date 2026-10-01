package moe.ouom.neriplayer.data.model.sync

data class SyncRemoteSnapshot<TVersion>(
    val data: SyncData?,
    val version: TVersion,
    val requiresMigrationUpload: Boolean = false
)

data class SyncUploadResolution<TMerged, TVersion>(
    val merged: TMerged,
    val remoteVersion: TVersion,
    val uploadPerformed: Boolean,
    val remoteChangedDuringSync: Boolean
)
