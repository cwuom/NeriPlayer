package moe.ouom.neriplayer.data.model.bilibili.music

import moe.ouom.neriplayer.ui.viewmodel.playlist.BiliVideoItem

/**
 * Bilibili 版权音乐榜榜单类型，对应接口 list_type。
 */
enum class BiliMusicRankType(val listType: Int, val cacheKey: String) {
    /** 热门榜 */
    HOT(1, "hot"),

    /** 原创榜 */
    ORIGINAL(2, "original");

    companion object {
        /** 按接口 list_type 反查榜单类型，未知取值回退到热门榜 */
        fun fromListType(listType: Int): BiliMusicRankType =
            values().firstOrNull { it.listType == listType } ?: HOT
    }
}

/**
 * 榜单期数（接口一年一段，按期数 ID 递增）。
 */
data class BiliMusicRankPeriod(
    val id: Long,
    val periodNumber: Int,
    val publishTimeSec: Long
)

/**
 * 版权音乐榜单曲目。
 *
 * 版权榜条目本身不携带可播放音频；播放优先使用官方 MV（mv_bvid，需先补全 mv_aid），
 * 缺失或补全失败时回退到榜单关联投稿（creation_*）。
 */
data class BiliMusicRankSong(
    val musicId: String,
    val rank: Int,
    val title: String,
    val singer: String,
    val album: String,
    /** 展示用封面（优先专辑封面，缺失时回退视频封面） */
    val coverUrl: String,
    /** 关联视频封面 */
    val videoCoverUrl: String,
    val creationAid: Long,
    val creationBvid: String,
    val creationCid: Long,
    val creationDurationSec: Int,
    val uploader: String,
    val uploaderMid: Long,
    val heat: Long,
    val canListen: Boolean,
    /**
     * 官方 MV（接口 mv_* 字段）。
     *
     * 列表可能缺少 mv_aid，加载榜单时按 mv_bvid 补全身份和官方 MV 元信息
     * 补全失败时保留已有身份，缺少有效身份才回退到榜单授权投稿
     */
    val mvAid: Long = 0L,
    val mvBvid: String = "",
    /** 官方 MV 时长（补全时带回，0 表示未知） */
    val mvDurationSec: Int = 0,
    /** 官方 MV 的上传账号名（补全时带回） */
    val mvUploader: String = "",
    /** 官方 MV 的视频封面（补全时带回，保证封面与播放源是同一支视频） */
    val mvCoverUrl: String = "",
    /** 官方 MV 的账号 ID，未知时保留 0，不能借用授权投稿的账号 */
    val mvUploaderMid: Long = 0L
) {
    /** 官方 MV 是否已具备完整媒体身份（aid + bvid） */
    val hasOfficialMv: Boolean
        get() = mvAid > 0L && mvBvid.isNotBlank()

    /** 实际播放条目的上传者：官方 MV 的账号优先，否则为榜单授权投稿的 UP 主 */
    val playbackUploader: String
        get() = if (hasOfficialMv) mvUploader.ifBlank { uploader } else uploader

    /** 实际播放视频的封面（官方 MV 封面优先，否则为投稿视频封面） */
    val videoCover: String
        get() = if (hasOfficialMv) mvCoverUrl.ifBlank { videoCoverUrl } else videoCoverUrl

    /**
     * 实际播放来源：官方 MV 优先，其次为榜单授权投稿；两者都不存在时为 [BiliMusicPlaybackSource.UNAVAILABLE]。
     *
     * 之所以要区分「无可播放源」而不是笼统归到 UPLOAD：SDK 侧有可能只带回 mv_bvid 但补全失败，
     * 同时 creation_* 也为空，此时条目是不可播放的，UI 必须如实告知用户，而不是显示一个点了没反应的来源标签。
     */
    val playbackSource: BiliMusicPlaybackSource
        get() = when {
            !isPlayable -> BiliMusicPlaybackSource.UNAVAILABLE
            hasOfficialMv -> BiliMusicPlaybackSource.OFFICIAL_MV
            else -> BiliMusicPlaybackSource.UPLOAD
        }

    /** 只有拿到完整视频身份时才允许入队播放 */
    val isPlayable: Boolean
        get() = hasOfficialMv || (creationAid > 0L && creationBvid.isNotBlank())

    /** 「打开原视频」使用的 BV 号（与实际播放来源一致） */
    val originalBvid: String
        get() = if (hasOfficialMv) mvBvid else creationBvid

    /** 展示用时长，官方 MV 优先 */
    val displayDurationSec: Int
        get() = if (hasOfficialMv && mvDurationSec > 0) mvDurationSec else creationDurationSec
}

/** 榜单曲目的实际播放来源，用于在 UI 上如实标注 */
enum class BiliMusicPlaybackSource {
    /** 官方 MV（接口 mv_bvid） */
    OFFICIAL_MV,

    /** 榜单授权的投稿视频（接口 creation_*） */
    UPLOAD,

    /** 官方 MV 与授权投稿都不完整，即无可播放音频 */
    UNAVAILABLE
}

/**
 * 音乐分区（rid=3）热门视频。
 */
data class BiliMusicHotVideo(
    val aid: Long,
    val bvid: String,
    val cid: Long,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val uploader: String,
    val uploaderMid: Long,
    val viewCount: Long,
    val danmakuCount: Long,
    val pubdateSec: Long,
    val tname: String
) {
    /** 拿到完整视频身份（aid + bvid）时才允许入队播放 */
    val isPlayable: Boolean
        get() = aid > 0L && bvid.isNotBlank()
}

/**
 * 转成现有的 B 站播放条目，无法播放时返回 null（调用方需过滤）。
 *
 * 播放源优先官方 MV（[BiliMusicRankSong.hasOfficialMv]），否则使用榜单授权投稿；两者都不可用时返回 null。
 * 上传者与封面都与实际播放的那支视频保持一致（[BiliMusicRankSong.playbackUploader] / [BiliMusicRankSong.videoCover]），
 * 因此播放页顶部显示的就是真实音源方，不会再出现「原唱名 + 别人视频」的错配。
 *
 * @return 可播放的 [BiliVideoItem]；既无官方 MV 也无有效投稿时返回 null
 */
fun BiliMusicRankSong.toPlayableBiliVideoItem(): BiliVideoItem? {
    if (hasOfficialMv) {
        return BiliVideoItem(
            id = mvAid,
            bvid = mvBvid,
            title = title.ifBlank { album },
            uploader = playbackUploader,
            uploaderMid = mvUploaderMid.coerceAtLeast(0L),
            coverUrl = videoCover,
            durationSec = displayDurationSec
        )
    }
    if (!isPlayable) return null
    return BiliVideoItem(
        id = creationAid,
        bvid = creationBvid,
        title = title.ifBlank { album },
        uploader = playbackUploader,
        uploaderMid = uploaderMid,
        coverUrl = videoCover,
        durationSec = creationDurationSec
    )
}

/**
 * 转成现有的 B 站播放条目，无法播放时返回 null（调用方需过滤）。
 *
 * @return 可播放的 [BiliVideoItem]；[BiliMusicHotVideo.isPlayable] 为 false 时返回 null
 */
fun BiliMusicHotVideo.toPlayableBiliVideoItem(): BiliVideoItem? {
    if (!isPlayable) return null
    return BiliVideoItem(
        id = aid,
        bvid = bvid,
        title = title,
        uploader = uploader,
        uploaderMid = uploaderMid,
        coverUrl = coverUrl,
        durationSec = durationSec
    )
}

/** 按榜单顺序提取可播放条目，顺序与榜单一致（不可播放的曲目被跳过） */
fun List<BiliMusicRankSong>.toPlayableRankVideoItems(): List<BiliVideoItem> =
    mapNotNull { it.toPlayableBiliVideoItem() }

/** 按榜单顺序提取可播放条目，顺序与榜单一致（不可播放的条目被跳过） */
fun List<BiliMusicHotVideo>.toPlayableHotVideoItems(): List<BiliVideoItem> =
    mapNotNull { it.toPlayableBiliVideoItem() }
