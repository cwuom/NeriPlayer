package moe.ouom.neriplayer.data.model.comments

/**
 * 统一的评论模型。
 *
 * 各平台的原始 JSON 只在 Mapper 层解析, 不向 UI 泄漏平台字段。
 */
data class SongComment(
    /** 平台内唯一的评论 id, 用于去重 (网易云 commentId / Bilibili rpid) */
    val id: String,
    val userId: String?,
    val username: String,
    val avatarUrl: String?,
    val content: String,
    val likeCount: Long,
    val replyCount: Long?,
    /** 发布时间的 Unix 毫秒时间戳 (各平台已归一化) */
    val createTime: Long?,
    val platform: CommentPlatform,
    /** 用户等级, 平台未提供时为 null */
    val userLevel: Int?,
    val isLiked: Boolean = false,
    val quotedComments: List<CommentQuote> = emptyList(),
    val previewReplies: List<SongComment> = emptyList(),
    val rootId: String? = null,
    /** 正文里可出现的内联表情 (标记 -> 图片地址), 为空表示正文是纯文本 */
    val emotes: List<CommentEmote> = emptyList(),
    /** 评论配图 (Bilibili `content.pictures`), 为空表示无图 */
    val images: List<CommentImage> = emptyList()
)

data class CommentQuote(
    val username: String,
    val content: String?
)

data class CommentReplyTarget(
    val commentId: String,
    val rootId: String,
    val username: String
)

/**
 * 各平台单条评论正文的长度上限: 网易云 140, Bilibili 1000。
 *
 * 发送前用它校验草稿长度, 超出上限的提交在本地就会被拦下。
 */
fun CommentPlatform.commentLengthLimit(): Int = when (this) {
    CommentPlatform.NETEASE -> 140
    CommentPlatform.BILIBILI -> 1000
}
