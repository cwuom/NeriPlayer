package moe.ouom.neriplayer.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import moe.ouom.neriplayer.data.model.ltw.track.ListenTogetherChannels
import moe.ouom.neriplayer.data.model.music.MusicPlatform
import moe.ouom.neriplayer.data.sync.model.SyncCausalToken

@Parcelize
data class SongItem(
    val id: Long,
    val name: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val durationMs: Long,
    val coverUrl: String?,
    val mediaUri: String? = null,
    val matchedLyric: String? = null,
    val matchedTranslatedLyric: String? = null,
    val matchedLyricSource: MusicPlatform? = null,
    val matchedSongId: String? = null,
    val userLyricOffsetMs: Long = 0L,
    val customCoverUrl: String? = null,
    val customName: String? = null,
    val customArtist: String? = null,
    val originalName: String? = null,
    val originalArtist: String? = null,
    val originalCoverUrl: String? = null,
    val originalLyric: String? = null,
    val originalTranslatedLyric: String? = null,
    val localFileName: String? = null,
    val localFilePath: String? = null,
    val channelId: String? = null,
    val audioId: String? = null,
    val subAudioId: String? = null,
    val playlistContextId: String? = null,
    val sourceStableKey: String? = null,
    val streamUrl: String? = null,
    val neteaseArtists: List<NeteaseArtistSummary>? = emptyList(),
    val addedAt: Long = 0L,
    val syncMembershipTokens: List<SyncCausalToken>? = emptyList(),
    val matchedRomanizedLyric: String? = null,
    val originalRomanizedLyric: String? = null,
    // logical creation time survives directory migration and provider renames
    val logicalCreatedAtMs: Long? = null,
    val createdAtSource: String? = null,
    val createdAtConfidence: String? = null,
    // playlist membership time is separate from the source file creation time
    val membershipAddedAtMs: Long? = null,
    // 保留迁移前的文件修改时间，复制后的 Provider 时间不能重排原有歌曲
    val sourceModifiedAtMs: Long? = null,
    val lyricSyncRevision: Long = 0L,
    // null 表示旧数据尚未区分用户修改与网络缓存
    val lyricSyncEdited: Boolean? = null,
    // 记录用户显式扫描选中的文件，不能由远端身份或侧载元数据推断
    val explicitLocalSourceReference: String? = null
) : Parcelable {
    /**
     * 播放渠道短标识，用于拼身份键（`bili:123`、`netease:123` 等）。
     *
     * 必须按渠道的**取值**判断，不能按「有没有值」判断：网易云歌曲的 `channelId`
     * 也是非空的（值为 `netease`），按存在性判断会把它们全当成 B 站，
     * 于是 bvid 恰好等于网易云歌曲 id 时两者身份相同，跳过降权、不喜欢名单、
     * 队列排除会互相串。
     */
    fun playbackSourceKey(): String {
        when (channelId?.trim()) {
            ListenTogetherChannels.BILIBILI -> return "bili"
            ListenTogetherChannels.YOUTUBE_MUSIC -> return "youtube"
            ListenTogetherChannels.NETEASE -> return "netease"
            ListenTogetherChannels.LOCAL -> return "local"
        }
        // 老数据可能没有 channelId，只能靠平台明细字段回推
        if (!neteaseArtists.isNullOrEmpty()) return "netease"
        if (album.startsWith(SongSourceTags.BILIBILI)) return "bili"
        return "local"
    }

    /**
     * 歌手身份键：优先用网易云返回的完整歌手列表（同名歌曲可能有多个艺人），
     * 回退到展示用的歌手名。放在这里是因为播放策略层的依赖边界不允许引用
     * 平台明细类型。
     */
    fun artistIdentityKey(): String {
        val fromSummary = neteaseArtists
            ?.mapNotNull { it.name?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.sorted()
            ?.joinToString("/")
            ?.lowercase()
            .orEmpty()
        return if (fromSummary.isNotEmpty()) fromSummary else artist.trim().lowercase()
    }
}
