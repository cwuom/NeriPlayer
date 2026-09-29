package moe.ouom.neriplayer.api.bilibili.model.search

data class SearchVideoItem(
    val aid: Long,
    val bvid: String,
    val titleHtml: String,
    val titlePlain: String,
    val author: String,
    val mid: Long,
    val coverUrl: String,
    val durationSec: Int,
    val play: Long?,
    val pubdate: Long?
)

data class SearchVideoPage(
    val page: Int,
    val pageSize: Int,
    val numResults: Int,
    val numPages: Int,
    val items: List<SearchVideoItem>
)
