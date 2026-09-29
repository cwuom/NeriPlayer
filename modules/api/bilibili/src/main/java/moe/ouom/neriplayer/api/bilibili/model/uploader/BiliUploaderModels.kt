package moe.ouom.neriplayer.api.bilibili.model.uploader

data class UploaderProfile(
    val mid: Long,
    val name: String,
    val faceUrl: String,
    val sign: String,
    val topPhotoUrl: String
)

data class UploaderVideo(
    val aid: Long,
    val bvid: String,
    val title: String,
    val coverUrl: String,
    val durationSec: Int,
    val uploaderMid: Long,
    val uploaderName: String,
    val play: Long?,
    val pubdate: Long?
)

data class UploaderVideoPage(
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val items: List<UploaderVideo>,
    val hasMore: Boolean
)

enum class UploaderContentKind {
    COLLECTION,
    SERIES
}

data class UploaderContent(
    val id: Long,
    val mid: Long,
    val kind: UploaderContentKind,
    val title: String,
    val coverUrl: String,
    val description: String,
    val total: Int
)

data class UploaderContentPage(
    val page: Int,
    val pageSize: Int,
    val collections: List<UploaderContent>,
    val series: List<UploaderContent>,
    val hasMore: Boolean
)
