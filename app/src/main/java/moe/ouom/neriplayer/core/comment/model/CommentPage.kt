package moe.ouom.neriplayer.core.comment.model

/** 评论分页每页条数 */
const val COMMENT_PAGE_SIZE = 20

/**
 * 一页评论数据。
 */
data class CommentPage(
    val comments: List<SongComment>,
    /** 页码, 从 1 开始 */
    val page: Int,
    val pageSize: Int,
    /** 评论总数, 平台未提供时为 null */
    val total: Long?,
    val hasMore: Boolean,
    val nextCursor: String? = null
)
