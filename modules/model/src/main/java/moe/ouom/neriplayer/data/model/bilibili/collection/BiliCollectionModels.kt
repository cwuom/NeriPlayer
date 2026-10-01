package moe.ouom.neriplayer.data.model.bilibili.collection

data class FavFolder(
    val mediaId: Long,
    val fid: Long,
    val mid: Long,
    val title: String,
    val coverUrl: String,
    val intro: String,
    val count: Int,
    val likeCount: Long?,
    val playCount: Long?,
    val collectCount: Long?,
    val upperName: String = "",
    val attr: Int = 0,
    val state: Int = 0,
    val itemType: Int = 11
)

data class FavResourceItem(
    val type: Int,           // 2: 视频稿件, 12: 音频, 21: 合集
    val id: Long,           // 对应 avid/auid/合集 id
    val bvid: String?,
    val title: String,
    val coverUrl: String,
    val intro: String,
    val durationSec: Int,
    val upperMid: Long,
    val upperName: String,
    val play: Long?,
    val danmaku: Long?,
    val favTime: Long?
)

data class FavResourcePage(
    val info: FavFolder,
    val items: List<FavResourceItem>,
    val hasMore: Boolean
)

data class CollectionMeta(
    val seasonId: Long,
    val mid: Long,
    val title: String,
    val coverUrl: String,
    val description: String,
    val total: Int
)

data class CollectionArchiveItem(
    val aid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val pubdate: Long?,
    val play: Long?
)

data class CollectionArchivePage(
    val meta: CollectionMeta,
    val items: List<CollectionArchiveItem>,
    val hasMore: Boolean
)
