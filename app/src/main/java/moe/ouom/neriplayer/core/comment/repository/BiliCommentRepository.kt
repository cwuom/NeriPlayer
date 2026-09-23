package moe.ouom.neriplayer.core.comment.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.core.comment.CommentApiException
import moe.ouom.neriplayer.core.comment.CommentMemoryCache
import moe.ouom.neriplayer.core.comment.mapper.parseBiliCommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPage
import moe.ouom.neriplayer.core.comment.model.CommentPlatform
import moe.ouom.neriplayer.core.di.AppContainer
import moe.ouom.neriplayer.core.logging.NPLogger

/** 旧版 Bilibili 歌曲把 avid 与分P序号打包进播放 id 时使用的进制 (见 `BiliSongResolver.resolveLegacy`) */
private const val BILI_LEGACY_ID_PACK_FACTOR = 10_000L

/** 每个资源 id 最多缓存多少个 legacy 解包结果, 避免长期运行后无界增长 */
private const val BILI_LEGACY_ID_CACHE_MAX = 32

/**
 * 把旧版 Bilibili 歌曲的「打包播放 id」还原为视频 avid。
 *
 * 旧版本把 `avid * 10000 + 分P序号` 直接存进播放 id, 项目播放侧在
 * `BiliSongResolver.resolveLegacy` 中同样用 `id / 10_000L` 反解;
 * 这里沿用同一规则, 使恢复的旧歌曲也能取到正确的评论 oid。
 *
 * 不像打包值时返回 null (负数 / 小于 10000 / 余数为 0)。
 */
internal fun legacyBiliResourceIdOrNull(resourceId: Long): Long? {
    if (resourceId < BILI_LEGACY_ID_PACK_FACTOR) return null
    val page = resourceId % BILI_LEGACY_ID_PACK_FACTOR
    if (page <= 0L) return null
    return (resourceId / BILI_LEGACY_ID_PACK_FACTOR).takeIf { it > 0L }
}

/**
 * Bilibili 评论仓库。
 *
 * 复用项目已有的 [AppContainer.biliClient] (同一条 HTTP / Cookie 链路)，
 * 不新建任何 Bilibili 网络层 (§16/§20/§35)。
 */
internal object BiliCommentRepository : CommentRepository {

    private const val TAG = "NERI-BiliComment"

    /** 打包播放 id -> 真实 avid, 记住解包结果后翻页 / 刷新不必再试错 */
    private val legacyResourceIds = LinkedHashMap<Long, Long>()

    override val platform: CommentPlatform = CommentPlatform.BILIBILI

    /**
     * 加载 Bilibili 某页评论: 非强制刷新时先查内存缓存, 未命中则以 aid 为资源 id
     * 调用 [AppContainer.biliClient] 的视频评论接口, 解析后写入缓存再返回。
     *
     * 恢复的旧版歌曲用的是打包播放 id, 直接用它会拿到 `-404` / 评论已关闭;
     * 因此请求失败时按播放侧同一规则解包重试一次, 只有重试成功才采用结果,
     * 真正「视频不存在 / 评论已关闭」的歌曲仍然保留原始错误。
     */
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

        val effectiveId = knownLegacyResourceId(resourceId) ?: resourceId

        // 只记录非敏感上下文, 不打印评论正文 / Cookie (§36)
        NPLogger.d(TAG, "load comments: platform=BILIBILI, resourceId=$effectiveId, page=$page")

        val result = try {
            loadPage(effectiveId, page, pageSize)
        } catch (apiError: CommentApiException) {
            resolveWithLegacyId(apiError, resourceId, effectiveId, page, pageSize)
        }

        CommentMemoryCache.put(platform.name, resourceId, page, result)
        return result
    }

    /**
     * 按解包后的 avid 重试一次; 成功时记住映射并返回该页,
     * 失败则抛出最初那次请求的错误 (保持「视频不存在 / 评论已关闭」的原判)。
     */
    private suspend fun resolveWithLegacyId(
        apiError: CommentApiException,
        resourceId: Long,
        effectiveId: Long,
        page: Int,
        pageSize: Int
    ): CommentPage {
        val legacyId = legacyBiliResourceIdOrNull(resourceId) ?: throw apiError
        if (legacyId == effectiveId) throw apiError

        val pageResult = try {
            loadPage(legacyId, page, pageSize)
        } catch (_: CommentApiException) {
            throw apiError
        }

        NPLogger.d(TAG, "legacy packed playback id decoded: $resourceId -> $legacyId")
        rememberLegacyResourceId(resourceId, legacyId)
        return pageResult
    }

    private suspend fun loadPage(resourceId: Long, page: Int, pageSize: Int): CommentPage {
        val root = withContext(Dispatchers.IO) {
            AppContainer.biliClient.getVideoComments(
                aid = resourceId,
                page = page,
                pageSize = pageSize
            )
        }

        return parseBiliCommentPage(root, page = page, pageSize = pageSize)
    }

    private fun knownLegacyResourceId(resourceId: Long): Long? = synchronized(legacyResourceIds) {
        legacyResourceIds[resourceId]
    }

    private fun rememberLegacyResourceId(resourceId: Long, legacyId: Long) {
        synchronized(legacyResourceIds) {
            if (legacyResourceIds[resourceId] == null &&
                legacyResourceIds.size >= BILI_LEGACY_ID_CACHE_MAX
            ) {
                legacyResourceIds.clear()
            }
            legacyResourceIds[resourceId] = legacyId
        }
    }
}
