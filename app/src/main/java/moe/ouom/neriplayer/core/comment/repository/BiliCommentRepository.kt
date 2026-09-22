package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.mapper.parseBiliCommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * Bilibili 评论仓库。
 *
 * 复用项目已有的 [AppContainer.biliClient] (同一条 HTTP / Cookie 链路)，
 * 不新建任何 Bilibili 网络层 (§16/§20/§35)。
 */
internal object BiliCommentRepository : CommentRepository {

    private const val TAG = "NERI-BiliComment"

    override val platform: CommentPlatform = CommentPlatform.BILIBILI

    override suspend fun loadComments(
        resourceId: Long,
        secondaryId: String?,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean
    ): CommentPage {
        if (!forceRefresh) {
            CommentMemoryCache.get(platform.name, resourceId, page)?.let { return it }
        }

        // 只记录非敏感上下文, 不打印评论正文 / Cookie (§36)
        NPLogger.d(TAG, "load comments: platform=BILIBILI, resourceId=$resourceId, page=$page")

        val root = withContext(Dispatchers.IO) {
            AppContainer.biliClient.getVideoComments(
                aid = resourceId,
                page = page,
                pageSize = pageSize
            )
        }

        val result = parseBiliCommentPage(root, page = page, pageSize = pageSize)
        CommentMemoryCache.put(platform.name, resourceId, page, result)
        return result
    }
}
