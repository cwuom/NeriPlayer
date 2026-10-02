@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.data.model.sync

import moe.ouom.neriplayer.data.sync.model.SyncCausalToken


import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import moe.ouom.neriplayer.data.model.playlist.LEGACY_SONG_ORDER_VERSION
import moe.ouom.neriplayer.data.model.SongIdentity
import moe.ouom.neriplayer.data.model.stableKey

@Serializable
data class SyncData(
    @ProtoNumber(1) val version: String = "2.0",
    // proto3 语义下标量默认值不写入报文, 桌面端可能省略, 解码侧必须提供默认值以免 MissingFieldException
    @ProtoNumber(2) val deviceId: String = "",
    @ProtoNumber(3) val deviceName: String = "",
    @ProtoNumber(4) val lastModified: Long = System.currentTimeMillis(),
    @ProtoNumber(5) val playlists: List<SyncPlaylist> = emptyList(),
    @ProtoNumber(6) val favoritePlaylists: List<SyncFavoritePlaylist> = emptyList(),
    @ProtoNumber(7) val recentPlays: List<SyncRecentPlay> = emptyList(),
    @ProtoNumber(8) val syncLog: List<SyncLogEntry> = emptyList(),
    @ProtoNumber(9) val recentPlayDeletions: List<SyncRecentPlayDeletion> = emptyList(),
    @ProtoNumber(10) val playbackStats: List<SyncTrackStat> = emptyList(),
    @ProtoNumber(11) val playbackStatsClearedAt: Long = 0L,
    @ProtoNumber(12) val playbackStatBuckets: List<SyncPlaybackStatBucket> = emptyList(),
    @ProtoNumber(13) val playlistSongDeletions: List<SyncPlaylistSongDeletion> = emptyList(),
    @ProtoNumber(14) val playlistUsageStats: List<SyncPlaylistUsageStat> = emptyList(),
    @ProtoNumber(15) val localPlaylistPlaybackStats: List<SyncLocalPlaylistPlaybackStat> = emptyList(),
    @ProtoNumber(16) val localPlaylistPlaybackBuckets: List<SyncLocalPlaylistPlaybackBucket> = emptyList(),
    @ProtoNumber(17) val biliVideoSkipRules: List<SyncBiliVideoSkipRule> = emptyList(),
    @ProtoNumber(18) val lyricOverrides: List<SyncSong> = emptyList(),
    @ProtoNumber(19) val playlistUsageDeletions: List<SyncPlaylistUsageDeletion> = emptyList()
)

@Serializable
data class SyncPlaylist(
    @ProtoNumber(1) val id: Long = 0L,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val songs: List<SyncSong> = emptyList(),
    @ProtoNumber(4) val createdAt: Long = 0L,
    @ProtoNumber(5) val modifiedAt: Long = 0L,
    @ProtoNumber(6) val isDeleted: Boolean = false,
    @ProtoNumber(7) val songOrderVersion: Int = LEGACY_SONG_ORDER_VERSION
) {
    companion object
}

@Serializable
data class SyncSong(
    @ProtoNumber(1) val id: Long = 0L,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val artist: String = "",
    @ProtoNumber(4) val album: String = "",
    @ProtoNumber(5) val albumId: Long = 0L,
    @ProtoNumber(6) val durationMs: Long = 0L,
    @ProtoNumber(7) val coverUrl: String? = null,
    @ProtoNumber(8) val mediaUri: String? = null,
    @ProtoNumber(9) val addedAt: Long = 0L,
    @ProtoNumber(10) val matchedLyric: String? = null,
    @ProtoNumber(11) val matchedTranslatedLyric: String? = null,
    @ProtoNumber(12) val matchedLyricSource: String? = null,
    @ProtoNumber(13) val matchedSongId: String? = null,
    @ProtoNumber(14) val userLyricOffsetMs: Long = 0L,
    @ProtoNumber(15) val customCoverUrl: String? = null,
    @ProtoNumber(16) val customName: String? = null,
    @ProtoNumber(17) val customArtist: String? = null,
    @ProtoNumber(18) val originalName: String? = null,
    @ProtoNumber(19) val originalArtist: String? = null,
    @ProtoNumber(20) val originalCoverUrl: String? = null,
    @ProtoNumber(21) val originalLyric: String? = null,
    @ProtoNumber(22) val originalTranslatedLyric: String? = null,
    @ProtoNumber(23) val channelId: String? = null,
    @ProtoNumber(24) val audioId: String? = null,
    @ProtoNumber(25) val subAudioId: String? = null,
    @ProtoNumber(26) val playlistContextId: String? = null,
    @ProtoNumber(27) val syncMembershipTokens: List<SyncCausalToken> = emptyList(),
    @ProtoNumber(28) val syncMetadataVersion: Int = LEGACY_SYNC_METADATA_VERSION,
    // legacy 快照迁移会重写 addedAt 以恢复展示顺序, 删除判定仍需保留原始值
    @ProtoNumber(29) val legacyAddedAt: Long? = null,
    @ProtoNumber(30) val lyricSyncRevision: Long = 0L,
    @ProtoNumber(31) val lyricSyncEdited: Boolean? = null,
    @ProtoNumber(32) val matchedRomanizedLyric: String? = null,
    @ProtoNumber(33) val originalRomanizedLyric: String? = null
) {
    companion object
}

@Serializable
data class SyncRecentPlay(
    @ProtoNumber(1) val songId: Long = 0L,
    @ProtoNumber(2) val song: SyncSong = SyncSong(),
    @ProtoNumber(3) val playedAt: Long = 0L,
    @ProtoNumber(4) val deviceId: String = "",
    @ProtoNumber(5) val resumePositionMs: Long = 0L
)

@Serializable
data class SyncRecentPlayDeletion(
    @ProtoNumber(1) val songId: Long = 0L,
    @ProtoNumber(2) val album: String = "",
    @ProtoNumber(3) val mediaUri: String? = null,
    @ProtoNumber(4) val deletedAt: Long = 0L,
    @ProtoNumber(5) val deviceId: String = ""
) {
    fun identity(): SongIdentity = SongIdentity(
        id = songId,
        album = album,
        mediaUri = mediaUri
    )

    fun stableKey(): String = identity().stableKey()
}

@Serializable
data class SyncPlaylistSongDeletion(
    @ProtoNumber(1) val playlistId: Long = 0L,
    @ProtoNumber(2) val songId: Long = 0L,
    @ProtoNumber(3) val album: String = "",
    @ProtoNumber(4) val mediaUri: String? = null,
    @ProtoNumber(5) val deletedAt: Long = 0L,
    @ProtoNumber(6) val deviceId: String = "",
    @ProtoNumber(7) val removedMembershipTokens: List<SyncCausalToken> = emptyList()
) {
    fun identity(): SongIdentity = SongIdentity(
        id = songId,
        album = album,
        mediaUri = mediaUri
    )

    fun stableKey(): String = "$playlistId|${identity().stableKey()}"
}

@Serializable
data class SyncFavoritePlaylist(
    @ProtoNumber(1) val id: Long = 0L,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val coverUrl: String? = null,
    @ProtoNumber(4) val trackCount: Int = 0,
    @ProtoNumber(5) val source: String = "",
    @ProtoNumber(6) val songs: List<SyncSong> = emptyList(),
    @ProtoNumber(7) val addedTime: Long = 0L,
    @ProtoNumber(8) val modifiedAt: Long = addedTime,
    @ProtoNumber(9) val isDeleted: Boolean = false,
    @ProtoNumber(10) val sortOrder: Long = addedTime,
    @ProtoNumber(11) val browseId: String? = null,
    @ProtoNumber(12) val playlistId: String? = null,
    @ProtoNumber(13) val subtitle: String? = null
) {
    companion object
}

@Serializable
data class SyncLogEntry(
    // action 枚举序数 0 (CREATE_PLAYLIST) 在 proto3 会被省略, 缺省值必须与 tag=0 语义一致
    @ProtoNumber(1) val timestamp: Long = 0L,
    @ProtoNumber(2) val deviceId: String = "",
    @ProtoNumber(3) val action: SyncAction = SyncAction.CREATE_PLAYLIST,
    @ProtoNumber(4) val playlistId: Long? = null,
    @ProtoNumber(5) val songId: Long? = null,
    @ProtoNumber(6) val details: String? = null
)

@Suppress("unused")
@Serializable
enum class SyncAction {
    CREATE_PLAYLIST,
    DELETE_PLAYLIST,
    RENAME_PLAYLIST,
    ADD_SONG,
    REMOVE_SONG,
    REORDER_SONGS,
    PLAY_SONG
}

data class SyncResult(
    val success: Boolean,
    val message: String,
    val playlistsAdded: Int = 0,
    val playlistsUpdated: Int = 0,
    val playlistsDeleted: Int = 0,
    val songsAdded: Int = 0,
    val songsRemoved: Int = 0,
    val conflicts: List<SyncConflict> = emptyList()
)

data class SyncConflict(
    val type: ConflictType,
    val playlistId: Long,
    val playlistName: String,
    val description: String,
    val resolution: ConflictResolution
)

@Suppress("unused")
enum class ConflictType {
    PLAYLIST_RENAMED_BOTH_SIDES,
    SONG_ADDED_REMOVED_CONFLICT,
    PLAYLIST_DELETED_MODIFIED_CONFLICT
}

@Suppress("unused")
enum class ConflictResolution {
    AUTO_MERGED,
    LOCAL_WINS,
    REMOTE_WINS,
    MANUAL_REQUIRED
}

@Serializable
data class SyncPlaybackCounterShard(
    @ProtoNumber(1) val deviceId: String = "",
    @ProtoNumber(2) val epochStartedAt: Long = 0L,
    @ProtoNumber(3) val totalListenMs: Long = 0L,
    @ProtoNumber(4) val playCount: Int = 0,
    @ProtoNumber(5) val firstPlayedAt: Long = 0L,
    @ProtoNumber(6) val lastPlayedAt: Long = 0L
)

@Serializable
data class SyncTrackStat(
    @ProtoNumber(1) val identityKey: String = "",
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val artist: String = "",
    @ProtoNumber(4) val album: String = "",
    @ProtoNumber(5) val totalListenMs: Long = 0L,
    @ProtoNumber(6) val playCount: Int = 0,
    @ProtoNumber(7) val lastPlayedAt: Long = 0L,
    @ProtoNumber(8) val firstPlayedAt: Long = 0L,
    @ProtoNumber(9) val coverUrl: String? = null,
    @ProtoNumber(10) val durationMs: Long = 0L,
    @ProtoNumber(11) val mediaUri: String? = null,
    @ProtoNumber(12) val id: Long = 0L,
    @ProtoNumber(13) val albumId: Long = 0L,
    @ProtoNumber(14) val counterBaseListenMs: Long = 0L,
    @ProtoNumber(15) val counterBasePlayCount: Int = 0,
    @ProtoNumber(16) val counterShards: List<SyncPlaybackCounterShard> = emptyList()
)

@Serializable
data class SyncPlaybackStatBucket(
    @ProtoNumber(1) val dayStartAt: Long = 0L,
    @ProtoNumber(2) val identityKey: String = "",
    @ProtoNumber(3) val name: String = "",
    @ProtoNumber(4) val artist: String = "",
    @ProtoNumber(5) val album: String = "",
    @ProtoNumber(6) val totalListenMs: Long = 0L,
    @ProtoNumber(7) val playCount: Int = 0,
    @ProtoNumber(8) val lastPlayedAt: Long = 0L,
    @ProtoNumber(9) val firstPlayedAt: Long = 0L,
    @ProtoNumber(10) val coverUrl: String? = null,
    @ProtoNumber(11) val durationMs: Long = 0L,
    @ProtoNumber(12) val mediaUri: String? = null,
    @ProtoNumber(13) val id: Long = 0L,
    @ProtoNumber(14) val albumId: Long = 0L,
    @ProtoNumber(15) val counterBaseListenMs: Long = 0L,
    @ProtoNumber(16) val counterBasePlayCount: Int = 0,
    @ProtoNumber(17) val counterShards: List<SyncPlaybackCounterShard> = emptyList()
)

@Serializable
data class SyncPlaylistUsageStat(
    @ProtoNumber(1) val playlistKey: String = "",
    @ProtoNumber(2) val source: String = "",
    @ProtoNumber(3) val id: Long = 0L,
    @ProtoNumber(4) val subtype: String? = null,
    @ProtoNumber(5) val name: String = "",
    @ProtoNumber(6) val coverUrl: String? = null,
    @ProtoNumber(7) val trackCount: Int = 0,
    @ProtoNumber(8) val lastOpenedAt: Long = 0L,
    @ProtoNumber(9) val firstOpenedAt: Long = 0L,
    @ProtoNumber(10) val openCount: Int = 0,
    @ProtoNumber(11) val counterBaseOpenCount: Long = 0L,
    @ProtoNumber(12) val counterShards: List<SyncPlaybackCounterShard> = emptyList(),
    @ProtoNumber(13) val fid: Long = 0L,
    @ProtoNumber(14) val mid: Long = 0L,
    @ProtoNumber(15) val browseId: String? = null,
    @ProtoNumber(16) val playlistId: String? = null,
    @ProtoNumber(17) val subtitle: String? = null,
    @ProtoNumber(18) val observedDeletionTokens: List<SyncCausalToken> = emptyList()
)

@Serializable
data class SyncPlaylistUsageDeletion(
    @ProtoNumber(1) val playlistKey: String = "",
    @ProtoNumber(2) val deletionTokens: List<SyncCausalToken> = emptyList(),
    @ProtoNumber(3) val deletedAt: Long = 0L
)

@Serializable
data class SyncLocalPlaylistPlaybackStat(
    @ProtoNumber(1) val playlistId: Long = 0L,
    @ProtoNumber(2) val totalPlayCount: Long = 0L,
    @ProtoNumber(3) val lastPlayedAt: Long = 0L,
    @ProtoNumber(4) val firstPlayedAt: Long = 0L,
    @ProtoNumber(5) val counterBasePlayCount: Long = 0L,
    @ProtoNumber(6) val counterShards: List<SyncPlaybackCounterShard> = emptyList()
)

@Serializable
data class SyncLocalPlaylistPlaybackBucket(
    @ProtoNumber(1) val dayStartAt: Long = 0L,
    @ProtoNumber(2) val playlistId: Long = 0L,
    @ProtoNumber(3) val playCount: Long = 0L,
    @ProtoNumber(4) val lastPlayedAt: Long = 0L,
    @ProtoNumber(5) val firstPlayedAt: Long = 0L,
    @ProtoNumber(6) val counterBasePlayCount: Long = 0L,
    @ProtoNumber(7) val counterShards: List<SyncPlaybackCounterShard> = emptyList()
)

const val LEGACY_SYNC_METADATA_VERSION = 0
const val CURRENT_SYNC_METADATA_VERSION = 1
