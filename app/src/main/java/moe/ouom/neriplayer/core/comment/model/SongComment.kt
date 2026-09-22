package moe.ouom.neriplayer.core.comment.model

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
    val userLevel: Int?
)
