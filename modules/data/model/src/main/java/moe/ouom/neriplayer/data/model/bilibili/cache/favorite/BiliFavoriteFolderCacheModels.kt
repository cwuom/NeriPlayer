package moe.ouom.neriplayer.data.model.bilibili.cache.favorite

data class CachedBiliFavoriteVideo(
    val id: Long,
    val bvid: String,
    val title: String,
    val uploader: String,
    val uploaderMid: Long = 0L,
    val coverUrl: String,
    val durationSec: Int
)

data class BiliFavoriteFolderContentCache(
    val mediaId: Long,
    val latestPageSignature: String,
    val totalCount: Int,
    val videos: List<CachedBiliFavoriteVideo>,
    val savedAtMs: Long = System.currentTimeMillis()
)
