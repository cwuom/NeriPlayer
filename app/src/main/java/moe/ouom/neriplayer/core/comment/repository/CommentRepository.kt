package moe.ouom.neriplayer.core.comment.repository

import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform

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
     * @param resourceId 网易云为歌曲 id, Bilibili 为 aid
     * @param secondaryId Bilibili 的 bvid (可能为 null)
     * @param forceRefresh true 时跳过缓存 (用于下拉刷新)
     */
    suspend fun loadComments(
        resourceId: Long,
        secondaryId: String?,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean = false
    ): CommentPage
}

/**
 * 按平台取对应的评论仓库。
 *
 * 后续接入 QQ 音乐 / 酷狗等平台时, 只需在此处增加分支 (任务书 §47)。
 */
internal fun commentRepositoryFor(platform: CommentPlatform): CommentRepository = when (platform) {
    CommentPlatform.NETEASE -> NeteaseCommentRepository
    CommentPlatform.BILIBILI -> BiliCommentRepository
}
