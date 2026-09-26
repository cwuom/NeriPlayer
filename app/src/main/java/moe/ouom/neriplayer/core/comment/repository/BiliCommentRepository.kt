package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.api.bili.BiliClient
import moe.ouom.neriplayer.core.api.bili.buildBiliSongAlbum
import moe.ouom.neriplayer.core.api.bili.resolveBiliSong
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.mapper.parseBiliCommentPage
import moe.ouom.neriplayer.core.comment.mapper.biliCommentError
import moe.ouom.neriplayer.core.comment.model.CommentError
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.comment.model.CommentSource
import moe.ouom.neriplayer.core.comment.model.CommentSort
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger
import moe.ouom.neriplayer.data.model.SongItem

internal class BiliCommentRepository(
    private val clientProvider: () -> BiliClient = { AppContainer.biliClient }
) : CommentRepository {

    override val platform: CommentPlatform = CommentPlatform.BILIBILI

    private val resolvedResourceIds = LinkedHashMap<CommentSource, Long>(16, 0.75f, true)

    override suspend fun loadComments(
        source: CommentSource,
        page: Int,
        pageSize: Int,
        forceRefresh: Boolean,
        sort: CommentSort,
        cursor: String?
    ): CommentPage {
        require(source.platform == platform)
        require(sort in CommentSort.supportedBy(platform))
        val resourceId = resolveResourceId(source)
        val client = clientProvider()
        val authenticated = client.hasCommentLogin()
        if ((forceRefresh && page == 1) || authenticated) {
            CommentMemoryCache.invalidate(platform.name, resourceId)
        }
        if (!forceRefresh && !authenticated) {
            CommentMemoryCache.get(platform.name, resourceId, page, sort, pageSize, cursor)?.let { return it }
        }

        NPLogger.d(TAG, "load comments: platform=BILIBILI, resourceId=$resourceId, page=$page")
        val root = withContext(Dispatchers.IO) {
            client.getVideoComments(resourceId, page, pageSize, if (sort == CommentSort.NEWEST) 0 else 1)
        }
        val result = parseBiliCommentPage(root, page, pageSize)
        if (!authenticated && !client.hasCommentLogin()) {
            CommentMemoryCache.put(platform.name, resourceId, page, result, sort, pageSize, cursor)
        }
        return result
    }

    override suspend fun setLiked(source: CommentSource, commentId: String, liked: Boolean) {
        require(source.platform == platform)
        val resourceId = resolveResourceId(source)
        try {
            val root = withContext(Dispatchers.IO) {
                clientProvider().setVideoCommentLiked(resourceId, commentId, liked)
            }
            val code = root.optInt("code", -1)
            if (code != 0) {
                throw CommentApiException(code, biliCommentError(code), "Bili comment like failed: $code")
            }
        } finally {
            CommentMemoryCache.invalidate(platform.name, resourceId)
        }
    }

    private suspend fun resolveResourceId(source: CommentSource): Long {
        synchronized(resolvedResourceIds) {
            resolvedResourceIds[source]?.let { return it }
        }

        // 打包 id 也可能命中另一条视频，成功的评论响应不能作为身份依据
        val song = SongItem(
            id = source.resourceId,
            name = source.resourceTitle.orEmpty(),
            artist = "",
            album = buildBiliSongAlbum(source.subResourceId, source.secondaryId),
            albumId = 0L,
            durationMs = 0L,
            coverUrl = null,
            channelId = "bilibili",
            audioId = source.resourceId.toString().takeIf { source.hasExplicitResourceId },
            subAudioId = source.subResourceId?.toString()
        )
        val resolved = withContext(Dispatchers.IO) {
            resolveBiliSong(song, clientProvider())
        }
        val requiresDirectId = source.hasExplicitResourceId &&
            source.secondaryId == null && source.subResourceId == null
        if (resolved == null || resolved.avid <= 0L ||
            (requiresDirectId && resolved.avid != source.resourceId) ||
            (source.secondaryId != null && resolved.videoInfo.bvid != source.secondaryId) ||
            (source.subResourceId != null && resolved.cid != source.subResourceId)
        ) {
            throw CommentApiException(
                code = -1,
                reason = CommentError.API,
                message = "Unable to verify Bili comment resource: ${source.resourceId}"
            )
        }

        synchronized(resolvedResourceIds) {
            resolvedResourceIds[source] = resolved.avid
            if (resolvedResourceIds.size > MAX_RESOLVED_RESOURCES) {
                val iterator = resolvedResourceIds.entries.iterator()
                iterator.next()
                iterator.remove()
            }
        }
        return resolved.avid
    }

    private companion object {
        const val TAG = "NERI-BiliComment"
        const val MAX_RESOLVED_RESOURCES = 32
    }
}
