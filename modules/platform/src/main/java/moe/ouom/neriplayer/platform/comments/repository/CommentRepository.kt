package moe.ouom.neriplayer.platform.comments.repository

import moe.ouom.neriplayer.data.model.comments.CommentPage
import moe.ouom.neriplayer.data.model.comments.CommentPlatform
import moe.ouom.neriplayer.data.model.comments.CommentSource
import moe.ouom.neriplayer.data.model.comments.CommentSort
import moe.ouom.neriplayer.data.model.comments.CommentReplyTarget

/**
 * 评论数据源统一接口。
 *
 * ViewModel / UI 只依赖本接口, 不接触任何平台 HTTP 细节 (§9/§10 分层要求)。
 */
interface CommentRepository {

    val platform: CommentPlatform

    suspend fun cachedComments(source: CommentSource, pageSize: Int, sort: CommentSort): CommentPage? = null

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
