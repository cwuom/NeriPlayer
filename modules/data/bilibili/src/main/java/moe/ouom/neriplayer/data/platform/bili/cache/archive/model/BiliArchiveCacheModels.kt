package moe.ouom.neriplayer.data.platform.bili.cache.archive.model

data class CachedBiliArchiveVideo(
    val id: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
    val uploaderMid: Long,
    val coverUrl: String,
    val durationSec: Int
)

data class BiliArchiveContentCache(
    val mediaId: Long,
    val kind: String,
    val totalCount: Int,
    val hasMore: Boolean,
    val videos: List<CachedBiliArchiveVideo>,
    val savedAtMs: Long = System.currentTimeMillis()
)
