package moe.ouom.neriplayer.core.comment.repository

import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget

/**
 * 评论数据源统一接口。
 *
 * ViewModel / UI 只依赖本接口, 不接触任何平台 HTTP 细节 (§9/§10 分层要求)。
 */
internal interface CommentRepository {

    val platform: CommentPlatform

    /**
     * 加载指定页的评论。
     *
     * @param source 包含平台资源及历史身份解析所需的信息
     * @param forceRefresh true 时跳过缓存 (用于下拉刷新)
     */
    suspend fun loadComments(
        source: CommentSource,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean = false,
        sort: CommentSort = CommentSort.HOT,
        cursor: String? = null
    ): CommentPage

    suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean)

    suspend fun loadReplies(
        source: CommentSource,
        rootId: String,
        page: Int,
        pageSize: Int,
        cursor: String? = null
    ): CommentPage

    suspend fun sendComment(source: CommentSource, content: String, target: CommentReplyTarget? = null)
}

private val neteaseCommentRepository = NeteaseCommentRepository()
private val biliCommentRepository = BiliCommentRepository()

/**
 * 按平台取对应的评论仓库。
 *
 * 后续接入 QQ 音乐 / 酷狗等平台时, 只需在此处增加分支 (任务书 §47)。
 */
internal fun commentRepositoryFor(platform: CommentPlatform): CommentRepository = when (platform) {
    CommentPlatform.NETEASE -> neteaseCommentRepository
    CommentPlatform.BILIBILI -> biliCommentRepository
}
