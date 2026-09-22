package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.mapper.parseNeteaseCommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 网易云评论仓库。
 *
 * 复用项目已有的 [AppContainer.neteaseClient] (同一条 HTTP / Cookie / 加密链路)，
 * 不新建任何网易云网络层 (§16/§20/§35)。
 */
internal object NeteaseCommentRepository : CommentRepository {

    private const val TAG = "NERI-NeteaseComment"

    override val platform: CommentPlatform = CommentPlatform.NETEASE

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
        NPLogger.d(TAG, "load comments: platform=NETEASE, resourceId=$resourceId, page=$page")

        val offset = (page - 1).coerceAtLeast(0) * pageSize
        val raw = withContext(Dispatchers.IO) {
            AppContainer.neteaseClient.getSongCommentsCancellable(
                songId = resourceId,
                limit = pageSize,
                offset = offset
            )
        }

        val result = parseNeteaseCommentPage(raw, page = page, pageSize = pageSize)
        CommentMemoryCache.put(platform.name, resourceId, page, result)
        return result
    }
}
