package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.netease.NeteaseClient
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.mapper.parseNeteaseCommentPage
import moe.ouom.neriplayer.core.comment.mapper.neteaseCommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import org.json.JSONObject
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger

/**
 * 网易云评论仓库。
 *
 * 复用项目已有的 [AppContainer.neteaseClient] (同一条 HTTP / Cookie / 加密链路)，
 * 不新建任何网易云网络层 (§16/§20/§35)。
 */
internal class NeteaseCommentRepository(
    private val clientProvider: () -> NeteaseClient = { AppContainer.neteaseClient }
) : CommentRepository {

    override val platform: CommentPlatform = CommentPlatform.NETEASE

    override suspend fun loadComments(
        source: CommentSource,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean,
        sort: CommentSort,
        cursor: String?
    ): CommentPage {
        require(source.platform == platform)
        val resourceId = source.resourceId
        val client = clientProvider()
        val authenticated = client.hasLogin()
        if ((forceRefresh && page == 1) || authenticated) {
            CommentMemoryCache.invalidate(platform.name, resourceId)
        }
        // 登录响应包含个人点赞状态，不放入跨账号共享的匿名缓存
        if (!forceRefresh && !authenticated) {
            CommentMemoryCache.get(platform.name, resourceId, page, sort, pageSize, cursor)?.let { return it }
        }

        // 只记录非敏感上下文, 不打印评论正文 / Cookie (§36)
        NPLogger.d(TAG, "load comments: platform=NETEASE, resourceId=$resourceId, page=$page")

        val raw = withContext(Dispatchers.IO) {
            client.getSongCommentsCancellable(
                songId = resourceId,
                page = page,
                pageSize = pageSize,
                sortType = when (sort) {
                    CommentSort.HOT -> 2
                    CommentSort.NEWEST -> 3
                    CommentSort.RECOMMENDED -> 99
                },
                cursor = cursor
            )
        }

        val result = parseNeteaseCommentPage(raw, page = page, pageSize = pageSize)
        val stalledTimeCursor = sort == CommentSort.NEWEST &&
            (result.nextCursor.isNullOrBlank() || result.nextCursor == cursor)
        if (result.hasMore && (result.comments.isEmpty() || stalledTimeCursor)) {
            throw CommentApiException(200, CommentError.API, "NetEase comment pagination did not advance")
        }
        if (!authenticated && !client.hasLogin()) {
            CommentMemoryCache.put(platform.name, resourceId, page, result, sort, pageSize, cursor)
        }
        return result
    }

    override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
        require(source.platform == platform)
        try {
            val root = JSONObject(withContext(Dispatchers.IO) {
                clientProvider().setSongCommentLiked(source.resourceId, commentId, liked)
            })
            val code = root.optInt("code", -1)
            if (code != 200) {
                throw CommentApiException(code, neteaseCommentError(code), "NetEase comment like failed: $code")
            }
        } finally {
            CommentMemoryCache.invalidate(platform.name, source.resourceId)
        }
    }

    private companion object {
        const val TAG = "NERI-NeteaseComment"
    }
}
