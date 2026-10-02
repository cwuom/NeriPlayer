package moe.ouom.neriplayer.data.model.stats

import moe.ouom.neriplayer.data.model.sync.SyncPlaybackCounterShard
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken

data class UsageEntry(
    val id: Long,
    val name: String,
    val picUrl: String?,
    val trackCount: Int,
    val source: String, // "netease" | "neteaseAlbum" | "bili" | "local" | "localArtist" | "youtubeMusic"
    val lastOpened: Long,
    val openCount: Int,
    val firstOpened: Long = lastOpened,
    val counterBaseOpenCount: Long = 0L,
    val counterShards: List<SyncPlaybackCounterShard> = emptyList(),
    val fid: Long? = null,
    val mid: Long? = null,
    val browseId: String? = null,
    val playlistId: String? = null,
    val subtype: String? = null,
    val subtitle: String? = null,
    val observedDeletionTokens: List<SyncCausalToken> = emptyList(),
)
