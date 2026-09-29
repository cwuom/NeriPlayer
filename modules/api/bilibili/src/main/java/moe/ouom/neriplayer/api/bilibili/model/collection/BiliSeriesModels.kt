package moe.ouom.neriplayer.api.bilibili.model.collection

data class SeriesArchivePage(
    val items: List<CollectionArchiveItem>,
    val total: Int,
    val hasMore: Boolean
)
