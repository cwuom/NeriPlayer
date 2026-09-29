package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.api.netease.client.NeteaseClient
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.mapper.parseNeteaseCommentPage
import moe.ouom.neriplayer.core.comment.mapper.parseNeteaseReplyPage
import moe.ouom.neriplayer.core.comment.model.CommentReplyTarget
import moe.ouom.neriplayer.core.comment.model.commentLengthLimit
import moe.ouom.neriplayer.core.comment.mapper.neteaseCommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import org.json.JSONObject
import moe.ouom.neriplayer.core.logging.NPLogger

/** 由宿主注入共享客户端，沿用相同的 HTTP、Cookie 和加密链路 */
class NeteaseCommentRepository(
    private val cache: CommentMemoryCache,
    private val clientProvider: () -> NeteaseClient
) : CommentRepository {

    override val platform: CommentPlatform = CommentPlatform.NETEASE

    override suspend fun cachedComments(source: CommentSource, pageSize: Int, sort: CommentSort): CommentPage? {
        require(source.platform == platform)
        return cache.get(platform.name, source.resourceId, 1, sort, pageSize,
            sessionKey = clientProvider().commentCacheSessionKey())
    }

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
        val sessionKey = client.commentCacheSessionKey()
        if (!forceRefresh) {
            cache.get(platform.name, resourceId, page, sort, pageSize, cursor, sessionKey)?.let { return it }
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
        requireNeteaseCommentProgress(result, sort, cursor)
        if (sessionKey == client.commentCacheSessionKey()) {
            if (forceRefresh && page == 1) cache.invalidate(platform.name, resourceId)
            cache.put(platform.name, resourceId, page, result, sort, pageSize, cursor, sessionKey)
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
            cache.invalidate(platform.name, source.resourceId)
        }
    }

    override suspend fun loadReplies(
        source: CommentSource, rootId: String, page: Int, pageSize: Int, cursor: String?
    ): CommentPage {
        require(source.platform == platform && page > 0)
        require(page == 1 || !cursor.isNullOrBlank())
        val raw = withContext(Dispatchers.IO) {
            clientProvider().getSongCommentReplies(source.resourceId, rootId, pageSize, cursor)
        }
        val result = parseNeteaseReplyPage(raw, page, pageSize)
        if (result.hasMore && (result.comments.isEmpty() || result.nextCursor == null || result.nextCursor == cursor)) {
            throw CommentApiException(200, CommentError.API, "NetEase replies did not advance")
        }
        return result
    }

    override suspend fun sendComment(source: CommentSource, content: String, target: CommentReplyTarget?) {
        require(source.platform == platform && content.isNotBlank() && content.length <= platform.commentLengthLimit())
        try {
            val root = JSONObject(withContext(Dispatchers.IO) {
                clientProvider().sendSongComment(source.resourceId, content, target?.commentId)
            })
            val code = root.optInt("code", -1)
            if (code != 200) {
                throw CommentApiException(code, neteaseCommentError(code), "NetEase comment send failed: $code")
            }
        } finally {
            cache.invalidate(platform.name, source.resourceId)
        }
    }

    private companion object {
        const val TAG = "NERI-NeteaseComment"
    }
}
